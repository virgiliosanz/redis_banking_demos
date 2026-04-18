package com.redis.workshop.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the MONITOR line parser and use-case inference.
 * No Redis, no Spring context — tests package-private static helpers.
 */
class RedisMonitorServiceTest {

    // ── parseQuotedArgs ─────────────────────────────────────────────────

    @Test
    void parseQuotedArgs_normalCommand() {
        List<String> args = RedisMonitorService.parseQuotedArgs("\"SET\" \"foo\" \"bar\"");
        assertThat(args).containsExactly("SET", "foo", "bar");
    }

    @Test
    void parseQuotedArgs_emptyInput_returnsEmptyList() {
        assertThat(RedisMonitorService.parseQuotedArgs("")).isEmpty();
        assertThat(RedisMonitorService.parseQuotedArgs("   ")).isEmpty();
    }

    @Test
    void parseQuotedArgs_escapedQuotesInsideArg() {
        // Input is the raw MONITOR format with a backslash-escaped quote in a value
        List<String> args = RedisMonitorService.parseQuotedArgs(
                "\"HSET\" \"key\" \"field\\\"with\\\"quotes\"");
        assertThat(args).containsExactly("HSET", "key", "field\"with\"quotes");
    }

    @Test
    void parseQuotedArgs_escapedBackslash() {
        // "\\" should become a single backslash in the parsed arg
        List<String> args = RedisMonitorService.parseQuotedArgs("\"SET\" \"k\" \"a\\\\b\"");
        assertThat(args).containsExactly("SET", "k", "a\\b");
    }

    @Test
    void parseQuotedArgs_longBinaryBlob_doesNotBreak() {
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < 50_000; i++) blob.append('x');
        String line = "\"SET\" \"bigkey\" \"" + blob + "\"";

        long t0 = System.currentTimeMillis();
        List<String> args = RedisMonitorService.parseQuotedArgs(line);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(args).hasSize(3);
        assertThat(args.get(0)).isEqualTo("SET");
        assertThat(args.get(1)).isEqualTo("bigkey");
        assertThat(args.get(2)).hasSize(50_000);
        // Manual parser must be linear — 50k chars should be far under a second
        assertThat(elapsed).isLessThan(1000L);
    }

    @Test
    void parseQuotedArgs_unterminatedQuote_doesNotThrow() {
        // Missing closing quote — parser must terminate cleanly
        List<String> args = RedisMonitorService.parseQuotedArgs("\"GET\" \"unterminated");
        assertThat(args).hasSize(2);
        assertThat(args.get(0)).isEqualTo("GET");
        assertThat(args.get(1)).isEqualTo("unterminated");
    }

    // ── inferUseCase ────────────────────────────────────────────────────

    @Test
    void inferUseCase_ucPrefixedKey_returnsUppercaseUseCase() {
        assertThat(RedisMonitorService.inferUseCase("uc1:token:abc", "HSET")).isEqualTo("UC1");
        assertThat(RedisMonitorService.inferUseCase("uc6:velocity:card", "ZADD")).isEqualTo("UC6");
        assertThat(RedisMonitorService.inferUseCase("uc11:stream:transactions", "XADD"))
                .isEqualTo("UC11");
    }

    @Test
    void inferUseCase_idxPrefixedKey_returnsUppercaseUseCase() {
        assertThat(RedisMonitorService.inferUseCase("idx:uc8:documents", "FT.SEARCH"))
                .isEqualTo("UC8");
        assertThat(RedisMonitorService.inferUseCase("idx:uc3:products", "FT.SEARCH"))
                .isEqualTo("UC3");
    }

    @Test
    void inferUseCase_emptyOrBlankKey_returnsEmpty() {
        assertThat(RedisMonitorService.inferUseCase("", "PING")).isEmpty();
        assertThat(RedisMonitorService.inferUseCase(null, "PING")).isEmpty();
    }

    @Test
    void inferUseCase_unrelatedKey_returnsEmpty() {
        assertThat(RedisMonitorService.inferUseCase("random:key", "GET")).isEmpty();
        assertThat(RedisMonitorService.inferUseCase("user:1001", "HGETALL")).isEmpty();
    }

    @Test
    void inferUseCase_ucKeyWithoutColon_returnsEmpty() {
        // Key starts with "uc" but has no ':' separator
        assertThat(RedisMonitorService.inferUseCase("ucTotalCount", "GET")).isEmpty();
    }
}
