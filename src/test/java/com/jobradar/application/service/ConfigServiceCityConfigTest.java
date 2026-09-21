package com.jobradar.application.service;

import com.jobradar.application.entity.LiepinConfigEntity;
import com.jobradar.application.mapper.ConfigMapper;
import com.jobradar.worker.liepin.LiepinConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigServiceCityConfigTest {

    private static final String MOJIBAKE_NATIONAL = "\u00e5\u0085\u00a8\u00e5\u009b\u00bd";

    @Test
    void workerConfigUsesTheSharedCityNormalizer() {
        ConfigMapper configMapper = mock(ConfigMapper.class);
        LiepinService liepinService = mock(LiepinService.class);
        LiepinConfigEntity entity = new LiepinConfigEntity();
        entity.setCity(MOJIBAKE_NATIONAL);

        when(liepinService.getFirstConfig()).thenReturn(entity);
        when(liepinService.normalizeCityToCode(MOJIBAKE_NATIONAL)).thenReturn("410");

        ConfigService service = new ConfigService(
                configMapper,
                liepinService,
                mock(BossService.class),
                mock(ZhilianService.class),
                mock(Job51Service.class)
        );

        LiepinConfig config = service.getLiepinConfig();

        assertEquals("410", config.getCityCode());
        verify(liepinService).normalizeCityToCode(MOJIBAKE_NATIONAL);
    }
}
