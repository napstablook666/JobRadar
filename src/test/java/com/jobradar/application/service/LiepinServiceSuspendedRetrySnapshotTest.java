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
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiepinServiceSuspendedRetrySnapshotTest {

    private Connection keepAlive;
    private LiepinService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:liepin_snapshot_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE liepin_data (job_id INTEGER PRIMARY KEY, job_title TEXT, job_link TEXT, job_description TEXT, job_salary_text TEXT, comp_name TEXT, hr_name TEXT, hr_id TEXT, hr_im_id TEXT, delivered INTEGER)");
            statement.execute("CREATE TABLE liepin_retry_queue (job_id INTEGER PRIMARY KEY, retry_reason TEXT NOT NULL, created_at DATETIME, updated_at DATETIME)");
            statement.execute("INSERT INTO liepin_data(job_id, job_title, job_link, job_description, job_salary_text, comp_name, hr_name, hr_id, hr_im_id, delivered) VALUES (2, '岗位2', 'https://example.test/2', '岗位职责2', '20K', '公司2', 'HR2', 'hr-2', 'im-2', 0), (3, '岗位3', 'https://example.test/3', '岗位职责3', '30K', '公司3', 'HR3', 'hr-3', 'im-3', NULL)");
            statement.execute("INSERT INTO liepin_retry_queue(job_id, retry_reason) VALUES (2, 'AI_TIMEOUT'), (3, 'NETWORK_UNCONFIRMED')");
        }
        service = new LiepinService(
                Mockito.mock(LiepinConfigMapper.class),
                Mockito.mock(LiepinOptionMapper.class),
                Mockito.mock(LiepinMapper.class),
                dataSource
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void snapshotKeepsOriginalLinkAndJobContext() {
        List<LiepinService.SuspendedRetryJob> jobs = service.listSuspendedRetryJobs();

        assertEquals(2, jobs.size());
        assertEquals(2L, jobs.get(0).jobId());
        assertEquals("https://example.test/2", jobs.get(0).jobLink());
        assertEquals("岗位职责2", jobs.get(0).jobDescription());
        assertEquals("20K", jobs.get(0).jobSalaryText());
        assertEquals("公司2", jobs.get(0).compName());
        assertEquals("hr-2", jobs.get(0).hrId());
        assertEquals("im-2", jobs.get(0).hrImId());
        assertEquals("AI_TIMEOUT", jobs.get(0).retryReason());
    }
}
