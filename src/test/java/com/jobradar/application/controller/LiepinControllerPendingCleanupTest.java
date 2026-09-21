package com.jobradar.application.controller;

import com.jobradar.application.service.LiepinService;
import com.jobradar.worker.manager.PlaywrightManager;
import com.jobradar.worker.service.LiepinJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinControllerPendingCleanupTest {

    private LiepinController controller;
    private LiepinJobService jobService;
    private LiepinService liepinService;
    private PlaywrightManager playwrightManager;

    @BeforeEach
    void setUp() {
        controller = new LiepinController();
        jobService = mock(LiepinJobService.class);
        liepinService = mock(LiepinService.class);
        playwrightManager = mock(PlaywrightManager.class);
        ReflectionTestUtils.setField(controller, "liepinJobService", jobService);
        ReflectionTestUtils.setField(controller, "liepinService", liepinService);
        ReflectionTestUtils.setField(controller, "playwrightManager", playwrightManager);
    }

    @Test
    void runningDeliveryReturnsConflictWithoutDeleting() {
        when(jobService.isRunning()).thenReturn(true);
        when(jobService.isAnyTaskRunning()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.clearPending();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        verify(liepinService, org.mockito.Mockito.never()).clearPendingSnapshots();
    }

    @Test
    void idleDeliveryReturnsCleanupCounts() {
        when(jobService.isRunning()).thenReturn(false);
        when(liepinService.clearPendingSnapshots())
                .thenReturn(new LiepinService.PendingCleanupResult(2, 1, 1, 0));

        ResponseEntity<Map<String, Object>> response = controller.clearPending();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(2L, response.getBody().get("deleted"));
        assertEquals(1L, response.getBody().get("delivered"));
        assertEquals(0L, response.getBody().get("pending"));
    }

    @Test
    void pendingSummaryReturnsGlobalCounts() {
        when(liepinService.getDeliverySummary())
                .thenReturn(new LiepinService.DeliverySummary(3, 1, 2));

        ResponseEntity<Map<String, Object>> response = controller.getPendingSummary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3L, response.getBody().get("total"));
        assertEquals(1L, response.getBody().get("delivered"));
        assertEquals(2L, response.getBody().get("pending"));
    }

    @Test
    void batchRetryEndpointStartsTheBatchRun() {
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(true);
        when(jobService.retryAiTimeoutsBatchAsync(any())).thenReturn(true);
        when(jobService.getRunId()).thenReturn(7L);

        ResponseEntity<Map<String, Object>> response = controller.retryAiTimeoutsBatch();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("started", response.getBody().get("status"));
        assertEquals(7L, response.getBody().get("runId"));
    }

    @Test
    void suspendedBatchRetryEndpointStartsFromQualifiedFailureSnapshot() {
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(true);
        when(jobService.retrySuspendedBatchAsync(any())).thenReturn(true);
        when(jobService.getSuspendedRetryRunId()).thenReturn(8L);
        when(jobService.getSuspendedRetryInitialPending()).thenReturn(2120);

        ResponseEntity<Map<String, Object>> response = controller.retrySuspendedBatch();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("started", response.getBody().get("status"));
        assertEquals(8L, response.getBody().get("runId"));
        assertEquals(2120, response.getBody().get("initialSuspended"));
    }

    @Test
    void suspendedRetryCanStartWhileNormalDeliveryIsRunning() {
        when(playwrightManager.isLoggedIn("liepin")).thenReturn(true);
        when(jobService.isRunning()).thenReturn(true);
        when(jobService.isSuspendedRetryRunning()).thenReturn(false);
        when(jobService.retrySuspendedBatchAsync(any())).thenReturn(true);
        when(jobService.getSuspendedRetryRunId()).thenReturn(9L);
        when(jobService.getSuspendedRetryInitialPending()).thenReturn(4);

        ResponseEntity<Map<String, Object>> response = controller.retrySuspendedBatch();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(9L, response.getBody().get("runId"));
    }

    @Test
    void stoppingSuspendedRetryDoesNotRequireNormalDeliveryToStop() {
        when(jobService.isSuspendedRetryRunning()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.stopSuspendedRetry();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        verify(jobService).stopSuspendedRetry();
    }

    @Test
    void cleanupChecksBothTaskSlots() {
        when(jobService.isAnyTaskRunning()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.clearPending();

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(liepinService, org.mockito.Mockito.never()).clearPendingSnapshots();
    }

    @Test
    void suspendedRetrySummaryOnlyUsesTheQualifiedFailureQueue() {
        when(liepinService.getSuspendedRetrySummary())
                .thenReturn(new LiepinService.SuspendedRetrySummary(3, 2, 1));

        ResponseEntity<Map<String, Object>> response = controller.getSuspendedRetrySummary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3L, response.getBody().get("total"));
        assertEquals(2L, response.getBody().get("ai"));
        assertEquals(1L, response.getBody().get("network"));
    }
}
