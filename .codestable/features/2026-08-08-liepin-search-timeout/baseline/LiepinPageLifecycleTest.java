package com.getjobs.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinPageLifecycleTest {

    @Test
    void recognizesPlaywrightTargetClosedMessage() {
        RuntimeException error = new RuntimeException(
                "TargetClosedError: Target page, context or browser has been closed");

        assertTrue(Liepin.isTargetClosedFailure(error));
    }

    @Test
    void recognizesNestedClosedPageCause() {
        RuntimeException cause = new RuntimeException("Page has been closed");
        RuntimeException error = new RuntimeException("operation failed", cause);

        assertTrue(Liepin.isTargetClosedFailure(error));
    }

    @Test
    void keepsOrdinaryTimeoutOutsideLifecycleFailure() {
        assertFalse(Liepin.isTargetClosedFailure(new RuntimeException("Timeout 5000ms exceeded")));
    }

    @Test
    void preservesSideEffectBoundary() {
        Liepin.PageLifecycleException beforeSend = new Liepin.PageLifecycleException(false,
                new RuntimeException("closed"));
        Liepin.PageLifecycleException afterSend = new Liepin.PageLifecycleException(true,
                new RuntimeException("closed"));

        assertFalse(beforeSend.sideEffectStarted());
        assertTrue(afterSend.sideEffectStarted());
    }
}
