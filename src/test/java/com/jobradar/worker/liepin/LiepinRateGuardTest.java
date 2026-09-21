package com.jobradar.worker.liepin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinRateGuardTest {

    @Test
    void accountActionsShareOneClockAndUseActionRanges() {
        AtomicLong now = new AtomicLong(0);
        List<String> messages = new ArrayList<>();
        LiepinRateGuard guard = newGuard(now, messages);

        assertTrue(guard.before(LiepinRateGuard.Action.SEARCH));
        assertTrue(guard.before(LiepinRateGuard.Action.PAGE));

        assertEquals(3_000L, now.get());
        assertTrue(messages.contains("节奏控制：翻页开始等待3秒"));
        assertTrue(messages.contains("节奏控制：翻页等待结束，继续处理"));
    }

    @Test
    void publishesWaitingBeforeSleepingAndCompletionAfterClockAdvances() {
        AtomicLong now = new AtomicLong(0);
        List<String> events = new ArrayList<>();
        LiepinRateGuard guard = newGuard(now, events, millis -> {
            events.add("sleep:" + millis);
            now.addAndGet(millis);
        }, message -> events.add("message:" + message));

        assertTrue(guard.before(LiepinRateGuard.Action.SEARCH));
        assertTrue(guard.before(LiepinRateGuard.Action.PAGE));

        int startIndex = events.indexOf("message:节奏控制：翻页开始等待3秒");
        int endIndex = events.indexOf("message:节奏控制：翻页等待结束，继续处理");
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex + 1);
        assertTrue(events.get(startIndex + 1).startsWith("sleep:"));
        assertEquals(3_000L, events.subList(startIndex + 1, endIndex).stream()
                .mapToLong(event -> Long.parseLong(event.substring("sleep:".length())))
                .sum());
    }

    @Test
    void reportsSatisfiedIntervalWithoutClaimingAnotherWait() {
        AtomicLong now = new AtomicLong(0);
        List<String> messages = new ArrayList<>();
        LiepinRateGuard guard = newGuard(now, messages);

        assertTrue(guard.before(LiepinRateGuard.Action.SEARCH));
        now.set(20_000L);

        assertTrue(guard.before(LiepinRateGuard.Action.PAGE));
        assertEquals(20_000L, now.get());
        assertTrue(messages.contains("节奏控制：翻页间隔已满足，无需额外等待"));
        assertFalse(messages.stream().anyMatch(message -> message.contains("翻页开始等待")));
    }

    @Test
    void stopDuringWaitPreventsTheActionFromContinuing() {
        AtomicLong now = new AtomicLong(0);
        AtomicBoolean shouldStop = new AtomicBoolean(false);
        List<String> messages = new ArrayList<>();
        LiepinRateGuard guard = new LiepinRateGuard(
                shouldStop::get,
                messages::add,
                now::get,
                millis -> {
                    shouldStop.set(true);
                    now.addAndGet(millis);
                },
                (min, max) -> min,
                new LiepinRateGuard.DelayRange(30, 60)
        );

        assertTrue(guard.before(LiepinRateGuard.Action.SEARCH));
        assertFalse(guard.before(LiepinRateGuard.Action.PAGE));
        assertTrue(messages.contains("节奏控制：翻页开始等待3秒"));
    }

    @Test
    void fifteenthSuccessfulSendStartsBatchCooldown() {
        AtomicLong now = new AtomicLong(0);
        LiepinRateGuard guard = newGuard(now, new ArrayList<>());

        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        for (int i = 0; i < 14; i++) {
            assertTrue(guard.afterSuccessfulSend(true));
        }
        assertEquals(0L, now.get());

        assertTrue(guard.afterSuccessfulSend(true));
        assertEquals(60_000L, now.get());
    }

    @Test
    void finalSuccessfulSendDoesNotStartUnusedBatchCooldown() {
        AtomicLong now = new AtomicLong(0);
        LiepinRateGuard guard = newGuard(now, new ArrayList<>());

        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        for (int i = 0; i < 14; i++) {
            assertTrue(guard.afterSuccessfulSend(true));
        }

        assertTrue(guard.afterSuccessfulSend(false));
        assertEquals(0L, now.get());
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
    void configFloorsLegacyDelayValuesToMinimumRanges() {
        LiepinConfig config = new LiepinConfig();
        config.setMaxPerRun(30);
        config.setMinDelaySeconds(2);
        config.setMaxDelaySeconds(5);
        config.setSearchMinDelaySeconds(1);
        config.setSearchMaxDelaySeconds(2);
        config.setPageMinDelaySeconds(1);
        config.setPageMaxDelaySeconds(2);
        config.setDetailMinDelaySeconds(1);
        config.setDetailMaxDelaySeconds(2);
        config.setBatchCooldownMinSeconds(1);
        config.setBatchCooldownMaxSeconds(2);

        assertEquals(30, config.effectiveMaxPerRun());
        assertEquals(10, config.effectiveMinDelaySeconds());
        assertEquals(10, config.effectiveMaxDelaySeconds());
        assertEquals(5, config.effectiveSearchMinDelaySeconds());
        assertEquals(5, config.effectiveSearchMaxDelaySeconds());
        assertEquals(3, config.effectivePageMinDelaySeconds());
        assertEquals(3, config.effectivePageMaxDelaySeconds());
        assertEquals(5, config.effectiveDetailMinDelaySeconds());
        assertEquals(5, config.effectiveDetailMaxDelaySeconds());
        assertEquals(60, config.effectiveBatchCooldownMinSeconds());
        assertEquals(60, config.effectiveBatchCooldownMaxSeconds());
    }

    @Test
    void productionPolicyUsesConfiguredRanges() {
        LiepinConfig config = new LiepinConfig();
        config.setSearchMinDelaySeconds(70);
        config.setSearchMaxDelaySeconds(80);
        config.setPageMinDelaySeconds(35);
        config.setPageMaxDelaySeconds(45);
        config.setDetailMinDelaySeconds(50);
        config.setDetailMaxDelaySeconds(60);
        config.setMinDelaySeconds(130);
        config.setMaxDelaySeconds(150);
        config.setRateGuardBatchSize(3);
        config.setBatchCooldownMinSeconds(600);
        config.setBatchCooldownMaxSeconds(700);

        AtomicLong now = new AtomicLong(0);
        LiepinRateGuard guard = new LiepinRateGuard(
                () -> false,
                ignored -> { },
                now::get,
                now::addAndGet,
                (min, max) -> min,
                new LiepinRateGuard.DelayRange(70, 80),
                new LiepinRateGuard.DelayRange(35, 45),
                new LiepinRateGuard.DelayRange(50, 60),
                new LiepinRateGuard.DelayRange(130, 150),
                3,
                new LiepinRateGuard.DelayRange(600, 700)
        );

        assertTrue(guard.before(LiepinRateGuard.Action.SEARCH));
        assertTrue(guard.before(LiepinRateGuard.Action.PAGE));
        assertEquals(35_000L, now.get());
    }

    @Test
    void detectsBusinessRiskSignalsBesidesHttpStatus() {
        assertEquals("请求频繁", Liepin.detectRiskSignal("message: 请求频繁，请稍后再试"));
        assertEquals("captcha", Liepin.detectRiskSignal("captcha required"));
        assertNull(Liepin.detectRiskSignal("normal job result"));
    }

    @Test
    void riskSignalEntersProbeThenRecoveryAndReturnsToNormal() {
        LiepinAccountPacing pacing = new LiepinAccountPacing();

        assertEquals(LiepinAccountPacing.RiskDisposition.LONG_REST,
                pacing.recordRiskSignal("响应429", true));
        assertEquals(LiepinAccountPacing.AdaptiveMode.RISK_REST, pacing.adaptiveMode());
        pacing.beginProbe();
        assertEquals(LiepinAccountPacing.AdaptiveMode.PROBE, pacing.adaptiveMode());

        for (int i = 0; i < LiepinConfig.ADAPTIVE_PROBE_MAX_SUCCESSFUL_SENDS; i++) {
            pacing.recordSuccessfulSend();
        }
        assertEquals(LiepinAccountPacing.AdaptiveMode.RECOVERY, pacing.adaptiveMode());

        for (int i = 0; i < LiepinConfig.ADAPTIVE_RECOVERY_MAX_SUCCESSFUL_SENDS; i++) {
            pacing.recordSuccessfulSend();
        }
        assertEquals(LiepinAccountPacing.AdaptiveMode.NORMAL, pacing.adaptiveMode());
    }

    @Test
    void repeatedRiskSignalRequiresManualConfirmation() {
        LiepinAccountPacing pacing = new LiepinAccountPacing();

        assertEquals(LiepinAccountPacing.RiskDisposition.LONG_REST,
                pacing.recordRiskSignal("captcha", true));
        pacing.beginProbe();

        assertEquals(LiepinAccountPacing.RiskDisposition.MANUAL_REQUIRED,
                pacing.recordRiskSignal("再次captcha", true));
        assertTrue(pacing.isManualRequired());
    }

    @Test
    void probeAndRecoveryUseLongerConfiguredSendIntervals() {
        AtomicLong now = new AtomicLong(0);
        List<String> messages = new ArrayList<>();
        LiepinAccountPacing pacing = new LiepinAccountPacing();
        LiepinRateGuard guard = new LiepinRateGuard(
                () -> false,
                messages::add,
                now::get,
                now::addAndGet,
                (min, max) -> min,
                new LiepinRateGuard.DelayRange(5, 10),
                new LiepinRateGuard.DelayRange(3, 5),
                new LiepinRateGuard.DelayRange(5, 8),
                new LiepinRateGuard.DelayRange(20, 35),
                15,
                new LiepinRateGuard.DelayRange(180, 300),
                pacing,
                true
        );

        assertEquals(LiepinAccountPacing.RiskDisposition.LONG_REST,
                pacing.recordRiskSignal("captcha", true));
        pacing.beginProbe();
        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        guard.afterSuccessfulSend(true);
        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        assertEquals(45_000L, now.get());
        guard.afterSuccessfulSend(true);
        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        assertEquals(90_000L, now.get());
        guard.afterSuccessfulSend(true);
        assertEquals(LiepinAccountPacing.AdaptiveMode.RECOVERY, pacing.adaptiveMode());
        assertTrue(guard.before(LiepinRateGuard.Action.SEND));
        assertEquals(120_000L, now.get());
        assertTrue(messages.contains("节奏控制：发送开始等待45秒"));
        assertTrue(messages.contains("节奏控制：发送开始等待30秒"));
    }

    @Test
    void manualRiskRecoveryDoesNotScheduleAnAutomaticRest() {
        AtomicLong now = new AtomicLong(0);
        LiepinAccountPacing pacing = new LiepinAccountPacing();
        LiepinRateGuard guard = new LiepinRateGuard(
                () -> false,
                ignored -> { },
                now::get,
                now::addAndGet,
                (min, max) -> min,
                new LiepinRateGuard.DelayRange(5, 10),
                new LiepinRateGuard.DelayRange(3, 5),
                new LiepinRateGuard.DelayRange(5, 8),
                new LiepinRateGuard.DelayRange(20, 35),
                15,
                new LiepinRateGuard.DelayRange(180, 300),
                pacing,
                false
        );

        guard.stopForSignal("响应429");

        assertTrue(guard.isStopped());
        assertFalse(guard.isRiskRestPending());
        assertEquals(LiepinAccountPacing.AdaptiveMode.MANUAL_REQUIRED, pacing.adaptiveMode());
    }

    private LiepinRateGuard newGuard(AtomicLong now, List<String> messages) {
        return newGuard(now, messages, now::addAndGet, messages::add);
    }

    private LiepinRateGuard newGuard(
            AtomicLong now,
            List<String> messages,
            LiepinRateGuard.Sleeper sleeper,
            java.util.function.Consumer<String> info
    ) {
        return new LiepinRateGuard(
                () -> false,
                info,
                now::get,
                sleeper,
                (min, max) -> min,
                new LiepinRateGuard.DelayRange(30, 60)
        );
    }
}
