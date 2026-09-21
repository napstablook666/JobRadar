package com.jobradar.worker.manager;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaywrightManagerLockTest {

    @Test
    void cancellableAccessStopsWaitingWithoutRunningAction() throws Exception {
        PlaywrightManager manager = new PlaywrightManager();
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> manager.withPlaywrightAccess(() -> {
            holderStarted.countDown();
            await(releaseHolder);
        }));

        assertTrue(holderStarted.await(1, TimeUnit.SECONDS));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean actionRan = new AtomicBoolean(false);
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() ->
                manager.withPlaywrightAccessCancellable(
                        cancelled::get,
                        () -> actionRan.set(true)
                )
        );

        Thread.sleep(50);
        cancelled.set(true);

        assertFalse(waiting.get(2, TimeUnit.SECONDS));
        assertFalse(actionRan.get());

        releaseHolder.countDown();
        holder.get(1, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
