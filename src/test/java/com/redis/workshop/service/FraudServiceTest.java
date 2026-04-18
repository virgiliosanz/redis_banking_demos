package com.redis.workshop.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for FraudService business logic — no Redis, no Spring context.
 * Exercises calculateRiskScore() and getRiskLevel() via package-private access.
 */
class FraudServiceTest {

    private final FraudService svc = new FraudService(null);

    // ── calculateRiskScore ──────────────────────────────────────────────

    @Test
    void lowRisk_noFactors_scoresZero() {
        int score = svc.calculateRiskScore(0, 100.0, false);
        assertThat(score).isZero();
        assertThat(svc.getRiskLevel(score)).isEqualTo("LOW");
    }

    @Test
    void criticalRisk_allFactors_capsAtHundred() {
        // velocity>=5 (40) + amount>=10000 (35) + geo (25) = 100
        int score = svc.calculateRiskScore(6, 12_000.0, true);
        assertThat(score).isEqualTo(100);
        assertThat(svc.getRiskLevel(score)).isEqualTo("CRITICAL");
    }

    @Test
    void velocityThresholds_mapToExpectedPoints() {
        assertThat(svc.calculateRiskScore(1, 0, false)).isZero();           // velocity < 2
        assertThat(svc.calculateRiskScore(2, 0, false)).isEqualTo(5);       // velocity 2
        assertThat(svc.calculateRiskScore(3, 0, false)).isEqualTo(20);      // MEDIUM threshold
        assertThat(svc.calculateRiskScore(5, 0, false)).isEqualTo(40);      // HIGH threshold
        assertThat(svc.calculateRiskScore(100, 0, false)).isEqualTo(40);    // stays at 40
    }

    @Test
    void amountThresholds_mapToExpectedPoints() {
        assertThat(svc.calculateRiskScore(0, 1999.99, false)).isZero();     // below 2000
        assertThat(svc.calculateRiskScore(0, 2000.0, false)).isEqualTo(8);  // 2000 floor
        assertThat(svc.calculateRiskScore(0, 5000.0, false)).isEqualTo(20); // medium threshold
        assertThat(svc.calculateRiskScore(0, 10_000.0, false)).isEqualTo(35); // high threshold
        assertThat(svc.calculateRiskScore(0, 99_999.0, false)).isEqualTo(35); // stays at 35
    }

    @Test
    void geoAnomalyAlone_adds25Points() {
        assertThat(svc.calculateRiskScore(0, 0, true)).isEqualTo(25);
        assertThat(svc.getRiskLevel(svc.calculateRiskScore(0, 0, true))).isEqualTo("MEDIUM");
    }

    @Test
    void scoreIsCappedAtOneHundred() {
        // Artificial oversized amount should not push score above 100
        int score = svc.calculateRiskScore(10, 1_000_000.0, true);
        assertThat(score).isEqualTo(100);
    }

    // ── getRiskLevel thresholds ──────────────────────────────────────────

    @Test
    void riskLevel_lowBoundary() {
        assertThat(svc.getRiskLevel(0)).isEqualTo("LOW");
        assertThat(svc.getRiskLevel(24)).isEqualTo("LOW");
    }

    @Test
    void riskLevel_mediumBoundary() {
        assertThat(svc.getRiskLevel(25)).isEqualTo("MEDIUM");
        assertThat(svc.getRiskLevel(44)).isEqualTo("MEDIUM");
    }

    @Test
    void riskLevel_highBoundary() {
        assertThat(svc.getRiskLevel(45)).isEqualTo("HIGH");
        assertThat(svc.getRiskLevel(69)).isEqualTo("HIGH");
    }

    @Test
    void riskLevel_criticalBoundary() {
        assertThat(svc.getRiskLevel(70)).isEqualTo("CRITICAL");
        assertThat(svc.getRiskLevel(100)).isEqualTo("CRITICAL");
    }
}
