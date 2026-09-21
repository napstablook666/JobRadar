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
    static final int BATCH_SIZE = 5;
    static final int BATCH_COOLDOWN_MIN_SECONDS = 1200;
    static final int BATCH_COOLDOWN_MAX_SECONDS = 1800;

    enum Action {
        SEARCH("搜索", 30, 60),
        PAGE("翻页", 15, 30),
        DETAIL("详情", 20, 40),
        SEND("发送", 90, 180);

        private final String label;
        private final int minSeconds;
        private final int maxSeconds;

        Action(String label, int minSeconds, int maxSeconds) {
            this.label = label;
            this.minSeconds = minSeconds;
            this.maxSeconds = maxSeconds;
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
    private final DelayRange sendRange;
    private long lastActivityAt = -1L;
    private int successfulSendCount;
    private boolean stopped;

    static LiepinRateGuard production(
            Supplier<Boolean> shouldStop,
            Consumer<String> info,
            int configuredSendMin,
            int configuredSendMax
    ) {
        int min = Math.max(LiepinConfig.MIN_SAFE_SEND_DELAY_SECONDS, configuredSendMin);
        int max = Math.max(LiepinConfig.DEFAULT_MAX_DELAY_SECONDS, configuredSendMax);
        return new LiepinRateGuard(
                shouldStop,
                info,
                System::currentTimeMillis,
                Thread::sleep,
                (low, high) -> java.util.concurrent.ThreadLocalRandom.current().nextInt(low, high + 1),
                new DelayRange(min, Math.max(min, max))
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
        this.shouldStop = shouldStop;
        this.info = info;
        this.clock = clock;
        this.sleeper = sleeper;
        this.randomSeconds = randomSeconds;
        this.sendRange = sendRange;
    }

    boolean before(Action action) {
        if (!isActive()) return false;
        DelayRange range = rangeFor(action);
        if (!waitFor(action.label, range)) return false;
        return isActive();
    }

    boolean afterSuccessfulSend() {
        successfulSendCount++;
        if (successfulSendCount % BATCH_SIZE != 0) return isActive();
        boolean cooled = waitFor("完成" + BATCH_SIZE + "次发送后的批次冷却",
                new DelayRange(BATCH_COOLDOWN_MIN_SECONDS, BATCH_COOLDOWN_MAX_SECONDS));
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
        return action == Action.SEND
                ? sendRange
                : new DelayRange(action.minSeconds, action.maxSeconds);
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
        lastActivityAt = clock.getAsLong();
        info.accept(String.format("节奏控制：%s等待%d秒后继续", label, delaySeconds));
        return true;
    }

    private boolean isActive() {
        return !stopped && !Boolean.TRUE.equals(shouldStop.get());
    }
}
