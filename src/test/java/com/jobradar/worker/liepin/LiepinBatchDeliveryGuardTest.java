package com.jobradar.worker.liepin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void relocatesTheChatButtonFromTheCurrentPageBeforeSending() {
        Page page = mock(Page.class);
        Locator cards = mock(Locator.class);
        Locator card = mock(Locator.class);
        Locator buttons = mock(Locator.class);
        Locator button = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(cards);
        when(cards.count()).thenReturn(1);
        when(cards.nth(0)).thenReturn(card);
        when(card.locator(anyString())).thenReturn(buttons);
        when(buttons.count()).thenReturn(1);
        when(buttons.nth(0)).thenReturn(button);
        when(button.isVisible()).thenReturn(true);
        when(button.textContent()).thenReturn("聊一聊");

        Liepin worker = new Liepin();
        worker.setPage(page);

        Locator relocated = ReflectionTestUtils.invokeMethod(worker,
                "findChatButtonForJob", null, 0);

        assertSame(button, relocated);
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

    @Test
    void pendingRetryTargetSetFiltersUnrelatedCards() {
        assertTrue(Liepin.shouldRetryPendingJob(Set.of(1L, 3L), 1L));
        assertFalse(Liepin.shouldRetryPendingJob(Set.of(1L, 3L), 2L));
        assertFalse(Liepin.shouldRetryPendingJob(Set.of(1L, 3L), null));
        assertTrue(Liepin.shouldRetryPendingJob(null, 2L));
    }
}
