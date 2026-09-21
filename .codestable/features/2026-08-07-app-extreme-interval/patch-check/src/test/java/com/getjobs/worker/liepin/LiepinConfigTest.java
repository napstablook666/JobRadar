package com.getjobs.worker.liepin;

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
    void intervalDefaultsUseExtremeAggressivePolicy() {
        LiepinConfig config = new LiepinConfig();

        assertEquals(30, config.effectiveMinDelaySeconds());
        assertEquals(60, config.effectiveMaxDelaySeconds());
        assertEquals(10, config.effectiveSearchMinDelaySeconds());
        assertEquals(20, config.effectiveSearchMaxDelaySeconds());
        assertEquals(5, config.effectivePageMinDelaySeconds());
        assertEquals(10, config.effectivePageMaxDelaySeconds());
        assertEquals(8, config.effectiveDetailMinDelaySeconds());
        assertEquals(15, config.effectiveDetailMaxDelaySeconds());
        assertEquals(300, config.effectiveBatchCooldownMinSeconds());
        assertEquals(450, config.effectiveBatchCooldownMaxSeconds());
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
}
