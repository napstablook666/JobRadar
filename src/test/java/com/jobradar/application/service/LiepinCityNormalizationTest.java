package com.jobradar.application.service;

import com.jobradar.application.entity.LiepinOptionEntity;
import com.jobradar.application.mapper.LiepinConfigMapper;
import com.jobradar.application.mapper.LiepinMapper;
import com.jobradar.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiepinCityNormalizationTest {

    private static final String MOJIBAKE_NATIONAL = "\u00e5\u0085\u00a8\u00e5\u009b\u00bd";

    private LiepinOptionMapper optionMapper;
    private LiepinService service;

    @BeforeEach
    void setUp() {
        optionMapper = mock(LiepinOptionMapper.class);
        service = new LiepinService(
                mock(LiepinConfigMapper.class),
                optionMapper,
                mock(LiepinMapper.class),
                mock(DataSource.class)
        );
    }

    @Test
    void knownNameResolvesToCode() {
        when(optionMapper.selectOne(any())).thenReturn(null, nationalOption());

        assertEquals("410", service.normalizeCityToCode("全国"));
    }

    @Test
    void knownCodeResolvesToCanonicalName() {
        when(optionMapper.selectOne(any())).thenReturn(nationalOption());

        assertEquals("全国", service.normalizeCityToName("410"));
    }

    @Test
    void utf8MojibakeResolvesToCode() {
        when(optionMapper.selectOne(any())).thenReturn(null, null, nationalOption());

        assertEquals("410", service.normalizeCityToCode(MOJIBAKE_NATIONAL));
        assertEquals("全国", LiepinService.restoreUtf8Mojibake(MOJIBAKE_NATIONAL));
    }

    @Test
    void canonicalUnicodeTextIsLeftUntouchedByRepairCandidate() {
        assertEquals("全国", LiepinService.restoreUtf8Mojibake("全国"));
    }

    @Test
    void blankCityMeansAllCities() {
        assertEquals("", service.normalizeCityToCode("  "));
    }

    @Test
    void unknownCityRaisesClearError() {
        when(optionMapper.selectOne(any())).thenReturn(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.normalizeCityToCode("不存在的城市")
        );

        assertTrue(error.getMessage().contains("不存在的城市"));
    }

    private static LiepinOptionEntity nationalOption() {
        LiepinOptionEntity option = new LiepinOptionEntity();
        option.setType("city");
        option.setName("全国");
        option.setCode("410");
        return option;
    }
}
