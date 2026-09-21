package com.jobradar.worker.job51;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Job51ConfigPacingTest {

    @Test
    void defaultsUseSteadyPacingProfile() {
        Job51Config config = new Job51Config();

        assertEquals(8, config.effectiveMaxPerRun());
        assertFalse(config.isStopAfterMaxPerRun());
        assertEquals(120, config.effectiveMaxPerRunRestMinSeconds());
        assertEquals(240, config.effectiveMaxPerRunRestMaxSeconds());
        assertEquals(20, config.effectiveMinDelaySeconds());
        assertEquals(40, config.effectiveMaxDelaySeconds());
        assertEquals(180, config.effectiveRestStage1MinSeconds());
        assertEquals(300, config.effectiveRestStage1MaxSeconds());
        assertEquals(600, config.effectiveRestStage2MinSeconds());
        assertEquals(900, config.effectiveRestStage2MaxSeconds());
        assertEquals(1800, config.effectiveRestStage3MinSeconds());
        assertEquals(2700, config.effectiveRestStage3MaxSeconds());
    }

    @Test
    void aiDelayCannotBeConfiguredBelowTwentySeconds() {
        Job51Config config = new Job51Config();
        config.setMinDelaySeconds(5);
        config.setMaxDelaySeconds(10);

        assertEquals(Job51Config.MIN_DELAY_SECONDS, config.effectiveMinDelaySeconds());
        assertEquals(Job51Config.MIN_DELAY_SECONDS, config.effectiveMaxDelaySeconds());
    }

    @Test
    void maxDelayAlwaysStaysAtOrAboveMinDelay() {
        Job51Config config = new Job51Config();
        config.setMinDelaySeconds(120);
        config.setMaxDelaySeconds(40);

        assertEquals(120, config.effectiveMinDelaySeconds());
        assertEquals(120, config.effectiveMaxDelaySeconds());
    }

    @Test
    void restRangesRemainOrderedWhenInputIsInvalid() {
        Job51Config config = new Job51Config();
        config.setRestStage1MinSeconds(500);
        config.setRestStage1MaxSeconds(300);

        assertEquals(500, config.effectiveRestStage1MinSeconds());
        assertEquals(500, config.effectiveRestStage1MaxSeconds());
        assertTrue(config.isRestEnabled());
    }
}
