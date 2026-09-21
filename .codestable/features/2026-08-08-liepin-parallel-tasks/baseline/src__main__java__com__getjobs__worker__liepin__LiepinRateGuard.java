package com.getjobs.worker.liepin;

import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 以账户为粒度串行化猎聘页面动作，避免搜索、详情和发送各自独立计时。
 * 这些值是保守的本地运行护栏，不代表平台公开阈值。
 */
final class LiepinRateGuard {
    static final int MAX_SAFE_PER_RUN = LiepinConfig.MAX_SAFE_PER_RUN;

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
    private long lastActivityAt = -1L;
    private int successfulSendCount;
    private boolean stopped;

    static LiepinRateGuard production(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            LiepinConfig config
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
                new DelayRange(config.effectiveBatchCooldownMinSeconds(), config.effectiveBatchCooldownMaxSeconds())
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
                new DelayRange(10, 20),
                new DelayRange(5, 10),
                new DelayRange(8, 15),
                sendRange,
                5,
                new DelayRange(300, 450)
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
    }

    boolean before(Action action) {
        if (!isActive()) return false;
        DelayRange range = rangeFor(action);
        if (!waitFor(action.label, range)) return false;
        return isActive();
    }

    void completed(Action action) {
        if (isActive()) {
            lastActivityAt = clock.getAsLong();
        }
    }

    boolean afterSuccessfulSend(boolean hasRemainingWork) {
        successfulSendCount++;
        if (!hasRemainingWork || successfulSendCount % batchSize != 0) return isActive();
        lastActivityAt = clock.getAsLong();
        boolean cooled = waitFor("完成" + batchSize + "次发送后的批次冷却", batchCooldownRange);
        if (cooled) info.accept("批次冷却完成，继续处理后续岗位");
        return cooled && isActive();
    }

    void stopForSignal(String reason) {
        stopped = true;
        info.accept("检测到平台风控信号，已停止任务：" + reason);
    }

    boolean isStopped() {
        return stopped;
    }

    private DelayRange rangeFor(Action action) {
        return switch (action) {
            case SEARCH -> searchRange;
            case PAGE -> pageRange;
            case DETAIL -> detailRange;
            case SEND -> sendRange;
        };
    }

    private boolean waitFor(String label, DelayRange range) {
        if (!isActive()) return false;
        int delaySeconds = randomSeconds.applyAsInt(range.minSeconds, range.maxSeconds);
        long now = clock.getAsLong();
        if (lastActivityAt < 0) {
            lastActivityAt = now;
            return true;
        }

        long remaining = lastActivityAt + delaySeconds * 1000L - now;
        if (remaining <= 0) {
            lastActivityAt = now;
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
            remaining = lastActivityAt + delaySeconds * 1000L - clock.getAsLong();
        }
        if (!isActive()) return false;
        lastActivityAt = clock.getAsLong();
        info.accept(String.format("节奏控制：%s等待结束，继续处理", label));
        return true;
    }

    private boolean isActive() {
        return !stopped && !Boolean.TRUE.equals(shouldStop.get());
    }
}
