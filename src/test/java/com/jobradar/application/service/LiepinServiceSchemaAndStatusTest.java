package com.jobradar.application.service;

import com.jobradar.application.mapper.LiepinConfigMapper;
import com.jobradar.application.mapper.LiepinMapper;
import com.jobradar.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinServiceSchemaAndStatusTest {
    private Connection keepAlive;
    private LiepinService service;
    private JobFunnelService funnelService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:liepin_schema_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            // Reproduce the old database before the new status/profile migration.
            statement.execute("CREATE TABLE liepin_data (job_id INTEGER PRIMARY KEY, delivered INTEGER, create_time DATETIME, update_time DATETIME)");
            statement.execute("CREATE TABLE liepin_config (id INTEGER PRIMARY KEY, keywords TEXT, city TEXT, salary_code TEXT)");
        }
        service = new LiepinService(
                Mockito.mock(LiepinConfigMapper.class),
                Mockito.mock(LiepinOptionMapper.class),
                Mockito.mock(LiepinMapper.class),
                dataSource
        );
        funnelService = new JobFunnelService(dataSource);
        funnelService.ensureTable();
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "jobFunnelService", funnelService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void migrationAddsStatusColumnsAndChatDoesNotEraseFormalSuccess() throws Exception {
        service.ensureTableExists();

        assertTrue(hasColumn("liepin_data", "chat_success"));
        assertTrue(hasColumn("liepin_data", "formal_apply_success"));
        assertTrue(hasColumn("liepin_data", "application_status"));
        assertTrue(hasColumn("liepin_config", "search_profile"));
        assertTrue(hasColumn("ai", "screen_prompt"));
        assertTrue(hasColumn("ai", "message_prompt"));
        assertTrue(hasColumn("ai", "jd_analysis_prompt"));

        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("INSERT INTO liepin_data(job_id, delivered, chat_success, formal_apply_success, application_status) "
                    + "VALUES (1, 1, 1, 1, 'APPLIED')");
            statement.execute("INSERT INTO liepin_data(job_id, delivered, chat_success, formal_apply_success, application_status) "
                    + "VALUES (2, 1, 0, 1, 'APPLIED')");
            statement.execute("INSERT INTO liepin_data(job_id, delivered, chat_success, formal_apply_success, application_status) "
                    + "VALUES (3, 0, 0, 0, 'PENDING')");
            statement.execute("INSERT INTO liepin_data(job_id, delivered, chat_success, formal_apply_success, application_status) "
                    + "VALUES (4, 0, 0, 0, 'PENDING')");
        }

        service.markChatSuccess(1L, false);

        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT chat_success, formal_apply_success, application_status FROM liepin_data WHERE job_id=1")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals(1, rows.getInt(2));
            assertEquals("APPLIED", rows.getString(3));
        }

        funnelService.retryable("liepin", 4L, "AI_TIMEOUT");
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("INSERT INTO liepin_retry_queue(job_id, retry_reason) VALUES (4, 'AI_TIMEOUT')");
        }

        LiepinService.ApplicationSummary summary = service.getApplicationSummary();
        assertEquals(4, summary.total());
        assertEquals(1, summary.chatSuccess());
        assertEquals(2, summary.formalApplySuccess());
        assertEquals(2, summary.pending());

        assertTrue(service.markFormalApplySuccess(4L));
        LiepinService.ApplicationSummary afterFormalApply = service.getApplicationSummary();
        assertEquals(3, afterFormalApply.formalApplySuccess());
        assertEquals(1, afterFormalApply.pending());

        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM liepin_retry_queue WHERE job_id=4")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        assertEquals(0L, funnelService.getFunnel("liepin").get("retryable"));
        Map<?, ?> stages = (Map<?, ?>) funnelService.getFunnel("liepin").get("stages");
        assertEquals(1L, stages.get("formal_apply_success"));
        assertFalse(service.markFormalApplySuccess(999L));
    }

    private boolean hasColumn(String table, String expected) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (expected.equalsIgnoreCase(rows.getString("name"))) return true;
            }
            return false;
        }
    }
}
