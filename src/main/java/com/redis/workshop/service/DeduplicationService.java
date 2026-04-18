package com.redis.workshop.service;

import com.redis.workshop.config.RedisCommandLogger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DeduplicationService {

    private static final String KEY_PREFIX = "workshop:dedup:";
    private static final long TTL_SECONDS = 300; // 5-minute dedup window
    private static final long WINDOW_SECONDS = 300; // floor to 5-min windows

    private final StringRedisTemplate redis;
    private final RedisCommandLogger commandLogger;
    private final List<Map<String, Object>> transactionLog = new CopyOnWriteArrayList<>();

    public DeduplicationService(StringRedisTemplate redis, RedisCommandLogger commandLogger) {
        this.redis = redis;
        this.commandLogger = commandLogger;
    }

    /**
     * Submit a transaction. Returns a result map with accepted/duplicate status.
     * Uses SET with NX + EX flags (SETNX pattern) for atomic check-and-set.
     */
    public Map<String, Object> submitTransaction(String sender, String receiver, String amount) {
        commandLogger.startCapture();
        String txHash = generateTxHash(sender, receiver, amount);
        String key = KEY_PREFIX + txHash;

        // SET key value NX EX 300  — atomic "set if not exists" with TTL
        Boolean wasSet = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(TTL_SECONDS));

        boolean accepted = Boolean.TRUE.equals(wasSet);
        String status = accepted ? "ACCEPTED" : "DUPLICATE";
        commandLogger.log("UC5", "SET NX EX", key, status,
                "SET " + key + " 1 NX EX " + TTL_SECONDS,
                accepted ? "OK (new key created)" : "nil (duplicate — key already exists)");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("txHash", txHash);
        result.put("sender", sender);
        result.put("receiver", receiver);
        result.put("amount", amount);
        result.put("timestamp", Instant.now().toString());
        result.put("redisKey", key);
        result.put("ttlSeconds", TTL_SECONDS);
        result.put("redisCommands", commandLogger.getCaptured());

        // Add to in-memory log for demo display (without the commands list, not needed historically)
        Map<String, Object> logEntry = new LinkedHashMap<>(result);
        logEntry.remove("redisCommands");
        transactionLog.add(0, logEntry);
        if (transactionLog.size() > 50) {
            transactionLog.remove(transactionLog.size() - 1);
        }

        return result;
    }

    /**
     * Get the transaction log for display.
     */
    public List<Map<String, Object>> getTransactionLog() {
        return Collections.unmodifiableList(transactionLog);
    }

    /**
     * Clear the transaction log (for demo reset).
     */
    public void clearLog() {
        transactionLog.clear();
    }

    /**
     * Generate a deterministic hash from transaction fields.
     * Uses sender + receiver + amount + time_window (floored to 5-min intervals).
     */
    String generateTxHash(String sender, String receiver, String amount) {
        long windowId = Instant.now().getEpochSecond() / WINDOW_SECONDS;
        String raw = sender.trim().toLowerCase() + ":"
                + receiver.trim().toLowerCase() + ":"
                + amount.trim() + ":"
                + windowId;
        return sha256Short(raw);
    }

    private String sha256Short(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) { // first 8 bytes = 16 hex chars
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
