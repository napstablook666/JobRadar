package com.jobradar.application.service;

import com.jobradar.application.mapper.Job51ConfigMapper;
import com.jobradar.application.mapper.Job51Mapper;
import com.jobradar.application.mapper.Job51OptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Job51ServiceDeliveryFunnelTest {
    private Connection keepAlive;
    private Job51Service service;
    private JobFunnelService funnelService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:job51_delivery_funnel_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE job51_data (job_id INTEGER PRIMARY KEY, delivered INTEGER DEFAULT 0, update_time TEXT)");
            statement.execute("INSERT INTO job51_data(job_id, delivered) VALUES (1, 0)");
            statement.execute("INSERT INTO job51_data(job_id, delivered) VALUES (2, 0)");
        }
        service = new Job51Service(
                Mockito.mock(Job51ConfigMapper.class),
                Mockito.mock(Job51OptionMapper.class),
                Mockito.mock(Job51Mapper.class),
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
    void singleDeliveryWritesFormalStageAndClearsRetryableOnlyAfterUpdate() throws Exception {
        funnelService.retryable("51job", 1L, "AI_TIMEOUT");
        funnelService.retryable("51job", 999L, "AI_TIMEOUT");

        service.markDelivered(1L);
        service.markDelivered(999L);

        assertEquals(1, deliveredValue(1L));
        Map<String, Object> funnel = funnelService.getFunnel("51job");
        Map<?, ?> stages = (Map<?, ?>) funnel.get("stages");
        assertEquals(1L, stages.get("formal_apply_success"));
        assertEquals(1L, funnel.get("retryable"));
    }

    @Test
    void batchDeliveryWritesOnlyActuallyUpdatedIds() throws Exception {
        funnelService.retryable("51job", 2L, "AI_TIMEOUT");
        funnelService.retryable("51job", 999L, "AI_TIMEOUT");

        service.markDeliveredBatch(List.of(2L, 999L));

        assertEquals(1, deliveredValue(2L));
        Map<String, Object> funnel = funnelService.getFunnel("51job");
        Map<?, ?> stages = (Map<?, ?>) funnel.get("stages");
        assertEquals(1L, stages.get("formal_apply_success"));
        assertEquals(1L, funnel.get("retryable"));
        assertTrue(funnelService.getFunnel("51job").containsKey("stages"));
    }

    private int deliveredValue(long jobId) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery("SELECT delivered FROM job51_data WHERE job_id=" + jobId)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
