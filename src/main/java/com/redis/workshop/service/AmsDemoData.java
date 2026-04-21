package com.redis.workshop.service;

import java.util.List;
import java.util.Map;

/**
 * Curated banking memories seeded into AMS long-term memory for the AMS use case.
 *
 * Intentionally a separate, self-contained dataset (NOT a reference to {@code MemoryService}):
 * UC9 must remain pedagogically independent, and the AMS case benefits from a
 * smaller, tighter subset optimized for demoing working-memory vs long-term-memory.
 *
 * Each entry maps 1:1 to an AMS {@code MemoryRecord}: client-provided {@code id}
 * for deterministic re-seed/reset, {@code text} as the searchable payload, plus
 * topics/entities used in the observability panel's "context assembly" view.
 */
public final class AmsDemoData {

    private AmsDemoData() {}

    public static final List<Map<String, Object>> LONG_TERM_MEMORIES = List.of(
            Map.of(
                    "id", "ams-mem-001",
                    "text", "Customer prefers SEPA for EUR transfers inside the EEA (€15 flat fee) and only uses SWIFT for non-EUR / non-EEA destinations where SEPA is unavailable.",
                    "topics", List.of("transfers", "fees", "preferences"),
                    "entities", List.of("SEPA", "SWIFT", "EEA"),
                    "memory_type", "semantic"
            ),
            Map.of(
                    "id", "ams-mem-002",
                    "text", "Customer holds a 25-year fixed mortgage at 3.2% and has asked about refinancing to a variable Euribor+0.9% product.",
                    "topics", List.of("mortgage", "refinancing"),
                    "entities", List.of("Euribor"),
                    "memory_type", "semantic"
            ),
            Map.of(
                    "id", "ams-mem-003",
                    "text", "Moderate risk profile. Interested in diversifying beyond equities into bond ETFs, real estate REITs and limited commodity exposure.",
                    "topics", List.of("investment", "risk-profile", "diversification"),
                    "entities", List.of("ETF", "REIT"),
                    "memory_type", "semantic"
            ),
            Map.of(
                    "id", "ams-mem-004",
                    "text", "Card was temporarily blocked and reissued after a fraud alert. 3D Secure and transaction alerts are enabled on the new card.",
                    "topics", List.of("card", "fraud", "security"),
                    "entities", List.of("3D Secure"),
                    "memory_type", "semantic"
            ),
            Map.of(
                    "id", "ams-mem-005",
                    "text", "Runs a fintech business; the business current account has Open Banking API access enabled (Business Pro plan) under PSD2.",
                    "topics", List.of("business", "open-banking", "psd2"),
                    "entities", List.of("PSD2", "Open Banking"),
                    "memory_type", "semantic"
            )
    );

    /** IDs of every seeded long-term memory, used for deterministic reset. */
    public static List<String> seededIds() {
        return LONG_TERM_MEMORIES.stream()
                .map(m -> (String) m.get("id"))
                .toList();
    }
}
