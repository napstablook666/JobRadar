package com.jobradar.worker.liepin;

import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 以账户为粒度串行化猎聘页面动作，避免搜索、详情和发送各自独立计时。
 * 这些值是保守的本地运行护栏，不代表平台公开阈值。
 */
final class LiepinRateGuard {
    enum Action {
        SEARCH("搜索"),
        PAGE("翻页"),
        DETAIL("详情"),
        SEND("发送");

        private final String label;

        Action(String label) {
            this.label = label;
        }
    }

    record DelayRange(int minSeconds, int maxSeconds) {
        DelayRange {
            if (minSeconds < 0 || maxSeconds < minSeconds) {
                throw new IllegalArgumentException("间隔范围无效");
            }
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Supplier<Boolean> shouldStop;
    private final Consumer<String> info;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final IntBinaryOperator randomSeconds;
    private final DelayRange searchRange;
    private final DelayRange pageRange;
    private final DelayRange detailRange;
    private final DelayRange sendRange;
    private final int batchSize;
    private final DelayRange batchCooldownRange;
    private final LiepinAccountPacing accountPacing;
    private final boolean automaticRiskRecoveryAllowed;
    private boolean stopped;

    static LiepinRateGuard production(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LiepinConfig config
    ) {
        return production(shouldStop, info, config, new LiepinAccountPacing());
    }

    static LiepinRateGuard production(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LiepinConfig config,
            LiepinAccountPacing accountPacing
    ) {
        return production(shouldStop, info, config, accountPacing, true);
    }

    static LiepinRateGuard production(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LiepinConfig config,
            LiepinAccountPacing accountPacing,
            boolean automaticRiskRecoveryAllowed
    ) {
        return new LiepinRateGuard(
                shouldStop,
                info,
                System::currentTimeMillis,
                Thread::sleep,
                (low, high) -> java.util.concurrent.ThreadLocalRandom.current().nextInt(low, high + 1),
                new DelayRange(config.effectiveSearchMinDelaySeconds(), config.effectiveSearchMaxDelaySeconds()),
                new DelayRange(config.effectivePageMinDelaySeconds(), config.effectivePageMaxDelaySeconds()),
                new DelayRange(config.effectiveDetailMinDelaySeconds(), config.effectiveDetailMaxDelaySeconds()),
                new DelayRange(config.effectiveMinDelaySeconds(), config.effectiveMaxDelaySeconds()),
                config.effectiveRateGuardBatchSize(),
                new DelayRange(config.effectiveBatchCooldownMinSeconds(), config.effectiveBatchCooldownMaxSeconds()),
                accountPacing,
                automaticRiskRecoveryAllowed
        );
    }

    LiepinRateGuard(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LongSupplier clock,
            Sleeper sleeper,
            IntBinaryOperator randomSeconds,
            DelayRange sendRange
    ) {
        this(
                shouldStop,
                info,
                clock,
                sleeper,
                randomSeconds,
                new DelayRange(5, 10),
                new DelayRange(3, 5),
                new DelayRange(5, 8),
                sendRange,
                15,
                new DelayRange(60, 120),
                new LiepinAccountPacing(),
                true
        );
    }

    LiepinRateGuard(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LongSupplier clock,
            Sleeper sleeper,
            IntBinaryOperator randomSeconds,
            DelayRange searchRange,
            DelayRange pageRange,
            DelayRange detailRange,
            DelayRange sendRange,
            int batchSize,
            DelayRange batchCooldownRange
    ) {
        this(
                shouldStop,
                info,
                clock,
                sleeper,
                randomSeconds,
                searchRange,
                pageRange,
                detailRange,
                sendRange,
                batchSize,
                batchCooldownRange,
                new LiepinAccountPacing(),
                true
        );
    }

    LiepinRateGuard(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LongSupplier clock,
            Sleeper sleeper,
            IntBinaryOperator randomSeconds,
            DelayRange searchRange,
            DelayRange pageRange,
            DelayRange detailRange,
            DelayRange sendRange,
            int batchSize,
            DelayRange batchCooldownRange,
            LiepinAccountPacing accountPacing
    ) {
        this(
                shouldStop,
                info,
                clock,
                sleeper,
                randomSeconds,
                searchRange,
                pageRange,
                detailRange,
                sendRange,
                batchSize,
                batchCooldownRange,
                accountPacing,
                true
        );
    }

    LiepinRateGuard(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LongSupplier clock,
            Sleeper sleeper,
            IntBinaryOperator randomSeconds,
            DelayRange searchRange,
            DelayRange pageRange,
            DelayRange detailRange,
            DelayRange sendRange,
            int batchSize,
            DelayRange batchCooldownRange,
            LiepinAccountPacing accountPacing,
            boolean automaticRiskRecoveryAllowed
    ) {
        this.shouldStop = shouldStop;
        this.info = info;
        this.clock = clock;
        this.sleeper = sleeper;
        this.randomSeconds = randomSeconds;
        this.searchRange = searchRange;
        this.pageRange = pageRange;
        this.detailRange = detailRange;
        this.sendRange = sendRange;
        this.batchSize = batchSize;
        this.batchCooldownRange = batchCooldownRange;
        this.accountPacing = accountPacing == null ? new LiepinAccountPacing() : accountPacing;
        this.automaticRiskRecoveryAllowed = automaticRiskRecoveryAllowed;
    }

    boolean before(Action action) {
        if (!isActive()) return false;
        DelayRange range = rangeFor(action);
        if (!waitFor(action.label, range)) return false;
        return isActive();
    }

    void completed(Action action) {
        if (isActive()) {
            accountPacing.recordActivity(clock.getAsLong());
        }
    }

    boolean afterSuccessfulSend(boolean hasRemainingWork) {
        int successfulSendCount = accountPacing.incrementSuccessfulSendCount();
        accountPacing.recordSuccessfulSend();
        if (!hasRemainingWork || successfulSendCount % batchSize != 0) return isActive();
        accountPacing.recordActivity(clock.getAsLong());
        boolean cooled = waitFor("完成" + batchSize + "次发送后的批次冷却", batchCooldownRange);
        if (cooled) info.accept("批次冷却完成，继续处理后续岗位");
        return cooled && isActive();
    }

    void stopForSignal(String reason) {
        stopForSignal(reason, automaticRiskRecoveryAllowed);
    }

    void stopForSignal(String reason, boolean automaticRecoveryAllowed) {
        if (stopped) return;
        stopped = true;
        LiepinAccountPacing.RiskDisposition disposition = accountPacing.recordRiskSignal(
                reason, automaticRecoveryAllowed && automaticRiskRecoveryAllowed);
        info.accept(disposition == LiepinAccountPacing.RiskDisposition.LONG_REST
                ? "检测到平台风控信号，已停止当前页并准备长休息：" + reason
                : "再次检测到平台风控信号，已停止自动恢复，需要人工确认：" + reason);
    }

    boolean isStopped() {
        return stopped || accountPacing.isManualRequired();
    }

    boolean isRiskRestPending() {
        return accountPacing.isRiskRestPending();
    }

    String riskReason() {
        return accountPacing.riskReason();
    }

    private DelayRange rangeFor(Action action) {
        return switch (action) {
            case SEARCH -> searchRange;
            case PAGE -> pageRange;
            case DETAIL -> detailRange;
            case SEND -> switch (accountPacing.adaptiveMode()) {
                case PROBE -> new DelayRange(
                        LiepinConfig.ADAPTIVE_PROBE_SEND_MIN_DELAY_SECONDS,
                        LiepinConfig.ADAPTIVE_PROBE_SEND_MAX_DELAY_SECONDS);
                case RECOVERY -> new DelayRange(
                        LiepinConfig.ADAPTIVE_RECOVERY_SEND_MIN_DELAY_SECONDS,
                        LiepinConfig.ADAPTIVE_RECOVERY_SEND_MAX_DELAY_SECONDS);
                default -> sendRange;
            };
        };
    }

    private boolean waitFor(String label, DelayRange range) {
        if (!isActive()) return false;
        int delaySeconds = randomSeconds.applyAsInt(range.minSeconds, range.maxSeconds);
        long now = clock.getAsLong();
        if (accountPacing.lastActivityAt() < 0) {
            accountPacing.recordActivity(now);
            return true;
        }

        long remaining = accountPacing.lastActivityAt() + delaySeconds * 1000L - now;
        if (remaining <= 0) {
            accountPacing.recordActivity(now);
            info.accept(String.format("节奏控制：%s间隔已满足，无需额外等待", label));
            return true;
        }

        info.accept(String.format("节奏控制：%s开始等待%d秒", label, delaySeconds));
        while (remaining > 0) {
            if (!isActive()) return false;
            try {
                sleeper.sleep(Math.min(250L, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stopped = true;
                info.accept("等待节奏间隔时收到停止信号");
                return false;
            }
            remaining = accountPacing.lastActivityAt() + delaySeconds * 1000L - clock.getAsLong();
        }
        if (!isActive()) return false;
        accountPacing.recordActivity(clock.getAsLong());
        info.accept(String.format("节奏控制：%s等待结束，继续处理", label));
        return true;
    }

    private boolean isActive() {
        return !stopped && !accountPacing.isManualRequired()
                && !Boolean.TRUE.equals(shouldStop.get());
    }
}
