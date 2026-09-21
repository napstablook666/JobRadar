package com.getjobs.worker.liepin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinRateGuardTest {

    @Test
    void accountActionsShareOneClockAndUseActionRanges() {
        AtomicLong now = new AtomicLong(0);
        List<String> messages = new ArrayList<>();
        LiepinRateGuard guard = newGuard(now, messages);

        assertTrue(guard.before(LiepinRateGuard.Action.SEARCH));
        assertTrue(guard.before(LiepinRateGuard.Action.PAGE));

        assertEquals(15_000L, now.get());
        assertTrue(messages.stream().anyMatch(message -> message.contains("翻页等待15秒")));
    }

    @Test
    void fifthSuccessfulSendStartsBatchCooldown() {
        AtomicLong now = new AtomicLong(0);
        LiepinRateGuard guard = newGuard(now, new ArrayList<>());

        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        for (int i = 0; i < 4; i++) {
            assertTrue(guard.afterSuccessfulSend());
        }
        assertEquals(0L, now.get());

        assertTrue(guard.afterSuccessfulSend());
        assertEquals(1_200_000L, now.get());
    }

    @Test
    void stopSignalPreventsAnotherAction() {
        AtomicLong now = new AtomicLong(0);
        LiepinRateGuard guard = newGuard(now, new ArrayList<>());

        guard.stopForSignal("响应429");

        assertFalse(guard.before(LiepinRateGuard.Action.SEARCH));
        assertTrue(guard.isStopped());
    }

    @Test
    void configFloorsLegacyValuesToConservativeDefaults() {
        LiepinConfig config = new LiepinConfig();
        config.setMaxPerRun(30);
        config.setMinDelaySeconds(2);
        config.setMaxDelaySeconds(5);

        assertEquals(10, config.effectiveMaxPerRun());
        assertEquals(90, config.effectiveMinDelaySeconds());
        assertEquals(180, config.effectiveMaxDelaySeconds());
    }

    private LiepinRateGuard newGuard(AtomicLong now, List<String> messages) {
        return new LiepinRateGuard(
                () -> false,
                messages::add,
                now::get,
                millis -> now.addAndGet(millis),
                (min, max) -> min,
                new LiepinRateGuard.DelayRange(90, 180)
        );
    }
}
