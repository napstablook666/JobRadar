package com.jobradar.application.controller;

import com.jobradar.application.service.JobFunnelService;
import com.jobradar.application.service.LiepinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinControllerFunnelAndApplicationTest {
    private LiepinController controller;
    private LiepinService liepinService;
    private JobFunnelService funnelService;

    @BeforeEach
    void setUp() {
        controller = new LiepinController();
        liepinService = mock(LiepinService.class);
        funnelService = mock(JobFunnelService.class);
        ReflectionTestUtils.setField(controller, "liepinService", liepinService);
        ReflectionTestUtils.setField(controller, "jobFunnelService", funnelService);
    }

    @Test
    void exposesLiepinFunnelByPlatform() {
        Map<String, Object> expected = Map.of("platform", "liepin", "retryable", 2L);
        when(funnelService.getFunnel("liepin")).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.getFunnel();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        verify(funnelService).getFunnel("liepin");
    }

    @Test
    void exposesDurableApplicationSummary() {
        LiepinService.ApplicationSummary expected = new LiepinService.ApplicationSummary(5, 3, 1, 2);
        when(liepinService.getApplicationSummary()).thenReturn(expected);

        assertEquals(expected, controller.getApplicationSummary());
    }

    @Test
    void formalApplicationEndpointMarksOnlyExplicitConfirmation() {
        when(liepinService.markFormalApplySuccess(42L)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.markFormalApplySuccess(42L);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(Boolean.TRUE.equals(response.getBody().get("success")));
        verify(liepinService).markFormalApplySuccess(42L);
    }
}
