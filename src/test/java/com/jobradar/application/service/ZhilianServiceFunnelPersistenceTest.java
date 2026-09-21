package com.jobradar.application.service;

import com.jobradar.application.entity.ZhilianJobDataEntity;
import com.jobradar.application.mapper.ZhilianConfigMapper;
import com.jobradar.application.mapper.ZhilianJobDataMapper;
import com.jobradar.application.mapper.ZhilianOptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianServiceFunnelPersistenceTest {
    private Connection keepAlive;
    private ZhilianService service;
    private ZhilianJobDataMapper jobDataMapper;
    private JobFunnelService funnelService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:zhilian_funnel_persistence_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        jobDataMapper = Mockito.mock(ZhilianJobDataMapper.class);
        service = new ZhilianService(
                Mockito.mock(ZhilianConfigMapper.class),
                Mockito.mock(ZhilianOptionMapper.class),
                jobDataMapper,
                dataSource
        );
        funnelService = new JobFunnelService(dataSource);
        funnelService.ensureTable();
        ReflectionTestUtils.setField(service, "jobFunnelService", funnelService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void jdStageRequiresAnActualPersistedUpdate() {
        Mockito.when(jobDataMapper.update(
                Mockito.any(ZhilianJobDataEntity.class), Mockito.any()))
                .thenReturn(0, 1);

        assertFalse(service.saveJobDescription("missing", "岗位 JD"));
        assertEquals(0L, stageCount("jd"));

        assertTrue(service.saveJobDescription("job-1", "岗位 JD"));
        assertEquals(1L, stageCount("jd"));
    }

    private long stageCount(String stage) {
        Map<String, Object> funnel = funnelService.getFunnel("zhilian");
        Map<?, ?> stages = (Map<?, ?>) funnel.get("stages");
        return ((Number) stages.get(stage)).longValue();
    }
}
