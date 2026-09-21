package com.getjobs.application.controller;

import com.getjobs.application.service.LiepinService;
import com.getjobs.worker.service.LiepinJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinControllerPendingCleanupTest {

    private LiepinController controller;
    private LiepinJobService jobService;
    private LiepinService liepinService;

    @BeforeEach
    void setUp() {
        controller = new LiepinController();
        jobService = mock(LiepinJobService.class);
        liepinService = mock(LiepinService.class);
        ReflectionTestUtils.setField(controller, "liepinJobService", jobService);
        ReflectionTestUtils.setField(controller, "liepinService", liepinService);
    }

    @Test
    void runningDeliveryReturnsConflictWithoutDeleting() {
        when(jobService.isRunning()).thenReturn(true);

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
}
