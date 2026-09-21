package com.jobradar.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LiepinConfigTest {

    @Test
    void autoDeliveryIsOffByDefaultAndScoreDefaultsToSeventy() {
        LiepinConfig config = new LiepinConfig();

        assertFalse(config.isAutoAiDeliveryEnabled());
        assertEquals(70, config.effectiveAiMinScore());
    }

    @Test
    void scoreIsClampedToValidRange() {
        LiepinConfig config = new LiepinConfig();

        config.setAiMinScore(-1);
        assertEquals(0, config.effectiveAiMinScore());
        config.setAiMinScore(101);
        assertEquals(100, config.effectiveAiMinScore());
    }

    @Test
    void intervalDefaultsUseAdaptiveNormalPolicy() {
        LiepinConfig config = new LiepinConfig();

        assertEquals(15, config.effectiveMaxPerRun());
        assertEquals(20, config.effectiveMinDelaySeconds());
        assertEquals(35, config.effectiveMaxDelaySeconds());
        assertEquals(5, config.effectiveSearchMinDelaySeconds());
        assertEquals(10, config.effectiveSearchMaxDelaySeconds());
        assertEquals(3, config.effectivePageMinDelaySeconds());
        assertEquals(5, config.effectivePageMaxDelaySeconds());
        assertEquals(5, config.effectiveDetailMinDelaySeconds());
        assertEquals(8, config.effectiveDetailMaxDelaySeconds());
        assertEquals(15, config.effectiveRateGuardBatchSize());
        assertEquals(180, config.effectiveBatchCooldownMinSeconds());
        assertEquals(300, config.effectiveBatchCooldownMaxSeconds());
    }

    @Test
    void keepsConfiguredLowerValidMaximumDelay() {
        LiepinConfig config = new LiepinConfig();
        config.setMinDelaySeconds(10);
        config.setMaxDelaySeconds(20);

        assertEquals(20, config.effectiveMaxDelaySeconds());
    }

    @Test
    void legacyAutoFlagStillSelectsSingleMode() {
        LiepinConfig config = new LiepinConfig();
        config.setAutoAiDelivery(true);

        assertEquals("SINGLE_AUTO", config.effectiveDeliveryMode());
        assertFalse(config.isBatchAutoDeliveryEnabled());
    }

    @Test
    void batchModeUsesBoundedDefaults() {
        LiepinConfig config = new LiepinConfig();
        config.setAiDeliveryMode("BATCH_AUTO");
        config.setAiBatchSize(100);
        config.setAiReviewMinScore(99);
        config.setAiMinScore(70);

        assertEquals("BATCH_AUTO", config.effectiveDeliveryMode());
        assertEquals(10, config.effectiveAiBatchSize());
        assertEquals(70, config.effectiveAiReviewMinScore());
    }

    @Test
    void aiTimeoutRetryUsesEnabledBoundedDefaults() {
        LiepinConfig config = new LiepinConfig();

        assertEquals(true, config.isAiTimeoutRetryEnabled());
        assertEquals(3, config.effectiveAiTimeoutMaxRetries());
        assertEquals(3, config.effectiveAiTimeoutRetryDelaySeconds());

        config.setAiTimeoutMaxRetries(99);
        config.setAiTimeoutRetryDelaySeconds(-1);
        assertEquals(10, config.effectiveAiTimeoutMaxRetries());
        assertEquals(0, config.effectiveAiTimeoutRetryDelaySeconds());
    }

    @Test
    void maxPerRunZeroMeansUnlimitedAndRestRangeIsConfigurable() {
        LiepinConfig config = new LiepinConfig();
        config.setMaxPerRun(0);
        config.setStopAfterMaxPerRun(false);
        config.setMaxPerRunRestMinSeconds(45);
        config.setMaxPerRunRestMaxSeconds(90);

        assertEquals(0, config.effectiveMaxPerRun());
        assertFalse(config.isStopAfterMaxPerRun());
        assertEquals(45, config.effectiveMaxPerRunRestMinSeconds());
        assertEquals(90, config.effectiveMaxPerRunRestMaxSeconds());
    }
}
