package com.redis.workshop.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens a dedicated TCP connection to Redis, issues MONITOR and parses the
 * streaming command feed. Keeps the last {@link #MAX_ENTRIES} entries in an
 * in-memory ring buffer so the workshop UI can poll them without affecting
 * other clients.
 *
 * <p>MONITOR is a blocking command on the connection it runs on, hence the
 * use of a raw socket here instead of the shared Lettuce connection factory.
 */
@Component
public class RedisMonitorService {

    private static final Logger log = LoggerFactory.getLogger(RedisMonitorService.class);
    private static final int MAX_ENTRIES = 100;

    // +<timestamp> [<db> <addr>] "<cmd>" "<arg1>" "<arg2>" ...
    private static final Pattern MONITOR_LINE = Pattern.compile(
            "^\\+?(\\d+\\.\\d+)\\s+\\[(\\d+)\\s+([^\\]]+)\\]\\s+(.*)$");
    private static final Pattern QUOTED_ARG = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*)\"");
    // Trim base64/hex vector blobs and long float arrays that would flood the UI
    private static final Pattern LONG_BLOB = Pattern.compile("\"[^\"]{200,}\"");

    private final RedisConnectionFactory connectionFactory;
    private final Deque<Map<String, Object>> buffer = new ConcurrentLinkedDeque<>();
    private final Map<String, Consumer<Map<String, Object>>> listeners = new ConcurrentHashMap<>();

    private volatile Thread worker;
    private volatile Socket socket;
    private volatile boolean running = false;

    public RedisMonitorService(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    public void start() {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            log.warn("RedisMonitorService: unsupported connection factory {}, MONITOR disabled",
                    connectionFactory.getClass().getName());
            return;
        }
        String host = lettuce.getHostName();
        int port = lettuce.getPort();
        String password = lettuce.getPassword();
        running = true;
        worker = new Thread(() -> runLoop(host, port, password), "redis-monitor");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (worker != null) worker.interrupt();
    }

    private void runLoop(String host, int port, String password) {
        while (running) {
            try (Socket s = new Socket(host, port)) {
                this.socket = s;
                s.setSoTimeout(0);
                OutputStream out = s.getOutputStream();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                if (password != null && !password.isEmpty()) {
                    out.write(resp("AUTH", password).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    String authReply = in.readLine();
                    if (authReply == null || !authReply.startsWith("+OK")) {
                        log.error("RedisMonitorService: AUTH failed: {}", authReply);
                        sleep(5000);
                        continue;
                    }
                }

                out.write(resp("MONITOR").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String monReply = in.readLine();
                if (monReply == null || !monReply.startsWith("+OK")) {
                    log.error("RedisMonitorService: MONITOR handshake failed: {}", monReply);
                    sleep(5000);
                    continue;
                }
                log.info("RedisMonitorService: connected to {}:{} and streaming MONITOR", host, port);

                String line;
                while (running && (line = in.readLine()) != null) {
                    addEntry(line);
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("RedisMonitorService: connection error ({}), reconnecting in 5s", e.getMessage());
                    sleep(5000);
                }
            }
        }
    }

    private static String resp(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ── Parsing & buffer ──────────────────────────────────────────────────

    private void addEntry(String rawLine) {
        String line = rawLine.startsWith("+") ? rawLine.substring(1) : rawLine;
        Matcher m = MONITOR_LINE.matcher(line);
        if (!m.matches()) return;

        double tsSeconds = Double.parseDouble(m.group(1));
        long tsMicros = (long) (tsSeconds * 1_000_000L);
        String db = m.group(2);
        String client = m.group(3);
        String cmdPart = m.group(4);

        List<String> args = new ArrayList<>();
        Matcher am = QUOTED_ARG.matcher(cmdPart);
        while (am.find()) {
            args.add(unescape(am.group(1)));
        }
        if (args.isEmpty()) return;

        String command = args.get(0).toUpperCase();
        String key = args.size() > 1 ? args.get(1) : "";
        String useCase = inferUseCase(key, command);
        String argsStr = renderArgs(args);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.ofEpochSecond(0, tsMicros * 1000L).toString());
        entry.put("tsMicros", tsMicros);
        entry.put("db", db);
        entry.put("client", client);
        entry.put("command", command);
        entry.put("key", key);
        entry.put("useCase", useCase);
        entry.put("args", argsStr);
        entry.put("fullCommand", command + (argsStr.isEmpty() ? "" : " " + argsStr));

        buffer.addFirst(entry);
        while (buffer.size() > MAX_ENTRIES) {
            buffer.removeLast();
        }

        for (Consumer<Map<String, Object>> listener : listeners.values()) {
            try {
                listener.accept(entry);
            } catch (Exception ignored) {
                // listener will be cleaned up by SSE error handling
            }
        }
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private static String renderArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.size(); i++) {
            if (i > 1) sb.append(' ');
            String a = args.get(i);
            if (a.length() > 200) {
                sb.append("<").append(a.length()).append("-byte blob>");
            } else {
                sb.append(a);
            }
        }
        String out = sb.toString();
        return LONG_BLOB.matcher(out).replaceAll("\"<blob>\"");
    }

    private static String inferUseCase(String key, String command) {
        if (key == null || key.isEmpty()) return "";
        if (key.startsWith("workshop:auth:")) return "UC1";
        if (key.startsWith("workshop:session:")) return "UC2";
        if (key.startsWith("workshop:profile:")) return "UC3";
        if (key.startsWith("workshop:ratelimit:")) return "UC4";
        if (key.startsWith("workshop:dedup:")) return "UC5";
        if (key.startsWith("workshop:fraud:")) return "UC6";
        if (key.startsWith("workshop:feature:")) return "UC7";
        if (key.startsWith("workshop:doc:") || key.startsWith("idx:docs")) return "UC8";
        if (key.startsWith("uc9:") || key.startsWith("idx:uc9:")) return "UC9";
        if (key.startsWith("workshop:cache:")) return "UC10";
        if (key.startsWith("workshop:txmonitor:")) return "UC11";
        if (key.startsWith("workshop:geo:") || key.startsWith("idx:branches")) return "UC12";
        if (key.startsWith("workshop:lock:")) return "UC13";
        return "";
    }

    // ── Public API ────────────────────────────────────────────────────────

    public List<Map<String, Object>> getRecentCommands(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> entry : buffer) {
            if (count >= limit) break;
            out.add(entry);
            count++;
        }
        return out;
    }

    public List<Map<String, Object>> getCommandsSince(Long sinceMicros, int limit) {
        if (sinceMicros == null) return getRecentCommands(limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> entry : buffer) {
            Long ts = (Long) entry.get("tsMicros");
            if (ts != null && ts > sinceMicros) {
                out.add(entry);
                if (out.size() >= limit) break;
            } else {
                break;
            }
        }
        return out;
    }

    public boolean isRunning() {
        return running;
    }

    public void addListener(String id, Consumer<Map<String, Object>> listener) {
        listeners.put(id, listener);
    }

    public void removeListener(String id) {
        listeners.remove(id);
    }
}
