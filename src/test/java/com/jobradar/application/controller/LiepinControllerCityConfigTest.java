package com.jobradar.application.controller;

import com.jobradar.application.entity.LiepinConfigEntity;
import com.jobradar.application.service.LiepinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinControllerCityConfigTest {

    private LiepinController controller;
    private LiepinService liepinService;

    @BeforeEach
    void setUp() {
        controller = new LiepinController();
        liepinService = mock(LiepinService.class);
        ReflectionTestUtils.setField(controller, "liepinService", liepinService);
    }

    @Test
    void savesCanonicalCityName() {
        LiepinConfigEntity request = new LiepinConfigEntity();
        request.setCity("410");
        LiepinConfigEntity saved = new LiepinConfigEntity();
        saved.setCity("全国");

        when(liepinService.normalizeCityToName("410")).thenReturn("全国");
        when(liepinService.saveOrUpdateFirstSelective(request)).thenReturn(saved);

        ResponseEntity<?> response = controller.updateConfig(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(saved, response.getBody());
        assertEquals("全国", request.getCity());
    }

    @Test
    void rejectsUnknownCityAsBadRequest() {
        LiepinConfigEntity request = new LiepinConfigEntity();
        request.setCity("不存在的城市");
        when(liepinService.normalizeCityToName("不存在的城市"))
                .thenThrow(new IllegalArgumentException("未在数据库中找到城市编码: 不存在的城市"));

        ResponseEntity<?> response = controller.updateConfig(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(false, body.get("success"));
        assertEquals("未在数据库中找到城市编码: 不存在的城市", body.get("message"));
        verify(liepinService, never()).saveOrUpdateFirstSelective(any());
    }
}
