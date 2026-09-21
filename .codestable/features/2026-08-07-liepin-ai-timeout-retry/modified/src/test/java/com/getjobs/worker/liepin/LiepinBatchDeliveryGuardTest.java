package com.getjobs.worker.liepin;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinBatchDeliveryGuardTest {

    @Test
    void unconfirmedSendKeepsTheCurrentPageForRetry() {
        assertEquals(Liepin.PageScanResult.RETRY, Liepin.pageResultAfterSend(false));
        assertEquals(Liepin.PageScanResult.COMPLETED, Liepin.pageResultAfterSend(true));
    }

    @Test
    void preparedJobStoresPositionInsteadOfAStaleLocator() {
        Class<?> preparedJob = Arrays.stream(Liepin.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("PreparedJob"))
                .findFirst()
                .orElseThrow();

        assertTrue(Arrays.stream(preparedJob.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("cardIndex")));
        assertFalse(Arrays.stream(preparedJob.getDeclaredFields())
                .anyMatch(field -> Locator.class.isAssignableFrom(field.getType())));
    }

    @Test
    void timeoutDetectionHandlesWrappedHttpTimeouts() {
        assertTrue(Liepin.isAiTimeoutFailure(new HttpTimeoutException("request timed out")));
        assertTrue(Liepin.isAiTimeoutFailure(new RuntimeException(new HttpTimeoutException("timeout"))));
        assertFalse(Liepin.isAiTimeoutFailure(new IllegalStateException("invalid JSON")));
    }

    @Test
    void aiSummaryExposesIndependentFailureCounters() {
        var summary = new Liepin().getAiSummary();
        assertEquals(0, summary.get("aiTimeouts"));
        assertEquals(0, summary.get("aiRetryAttempts"));
        assertEquals(0, summary.get("aiRetryable"));
        assertEquals(0, summary.get("invalidResults"));
        assertEquals(0, summary.get("buttonFailures"));
    }
}
