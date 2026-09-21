package com.jobradar.application.service;

import com.jobradar.application.entity.LiepinConfigEntity;
import com.jobradar.application.mapper.LiepinConfigMapper;
import com.jobradar.application.mapper.LiepinMapper;
import com.jobradar.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LiepinServiceKeywordNormalizationTest {

    @Test
    void updateConfigStoresRepairedKeywordsAsJson() {
        LiepinService service = new LiepinService(
                mock(LiepinConfigMapper.class),
                mock(LiepinOptionMapper.class),
                mock(LiepinMapper.class),
                mock(DataSource.class)
        );
        LiepinConfigEntity config = new LiepinConfigEntity();
        config.setId(1L);
        config.setKeywords(doubleMojibake("放射技师"));

        service.updateConfig(config);

        assertEquals("[\"放射技师\"]", config.getKeywords());
    }

    private static String doubleMojibake(String value) {
        String once = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return new String(once.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }
}
