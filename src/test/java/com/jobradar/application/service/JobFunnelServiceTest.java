package com.jobradar.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobFunnelServiceTest {
    private Connection keepAlive;
    private JobFunnelService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:funnel_test_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        service = new JobFunnelService(dataSource);
        service.ensureTable();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void aggregatesLiepinStagesAndRetryableStateByPlatform() {
        service.collected("liepin", 1L);
        service.detailLink("liepin", 1L);
        service.jd("liepin", 1L);
        service.aiValid("liepin", 1L);
        service.aiPass("liepin", 1L);
        service.buttonVisible("liepin", 1L);
        service.chatSuccess("liepin", 1L);
        service.retryable("liepin", 2L, "AI_TIMEOUT");
        service.formalApplySuccess("51job", 3L);

        Map<String, Object> result = service.getFunnel("liepin");
        Map<?, ?> stages = (Map<?, ?>) result.get("stages");
        assertEquals(1L, stages.get("collected"));
        assertEquals(1L, stages.get("detail_link"));
        assertEquals(1L, stages.get("jd"));
        assertEquals(1L, stages.get("ai_valid"));
        assertEquals(1L, stages.get("ai_pass"));
        assertEquals(1L, stages.get("button_visible"));
        assertEquals(1L, stages.get("chat_success"));
        assertEquals(0L, stages.get("formal_apply_success"));
        assertEquals(1L, result.get("retryable"));
    }
}
