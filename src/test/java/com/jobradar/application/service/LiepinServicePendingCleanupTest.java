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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiepinServicePendingCleanupTest {

    private Connection keepAlive;
    private LiepinService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:liepin_cleanup_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();

        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE liepin_data (job_id INTEGER PRIMARY KEY, delivered INTEGER)");
            statement.execute("CREATE TABLE liepin_page_progress (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE liepin_retry_queue (job_id INTEGER PRIMARY KEY, retry_reason TEXT NOT NULL, created_at DATETIME, updated_at DATETIME)");
            statement.execute("INSERT INTO liepin_data(job_id, delivered) VALUES (1, 1), (2, 0), (3, NULL)");
            statement.execute("INSERT INTO liepin_retry_queue(job_id, retry_reason) VALUES (2, 'AI_TIMEOUT'), (3, 'NETWORK_UNCONFIRMED')");
            statement.execute("INSERT INTO liepin_page_progress(id) VALUES (1)");
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
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    @Test
    void clearsZeroAndNullButPreservesDeliveredAndPageProgress() throws Exception {
        LiepinService.DeliverySummary before = service.getDeliverySummary();
        assertEquals(new LiepinService.DeliverySummary(3, 1, 2), before);

        LiepinService.PendingCleanupResult result = service.clearPendingSnapshots();

        assertEquals(new LiepinService.PendingCleanupResult(2, 1, 1, 0), result);
        try (Statement statement = keepAlive.createStatement();
             ResultSet jobs = statement.executeQuery("SELECT job_id FROM liepin_data ORDER BY job_id")) {
            jobs.next();
            assertEquals(1, jobs.getLong(1));
        }
        try (Statement statement = keepAlive.createStatement();
             ResultSet progress = statement.executeQuery("SELECT COUNT(*) FROM liepin_page_progress")) {
            progress.next();
            assertEquals(1, progress.getInt(1));
        }
        try (Statement statement = keepAlive.createStatement();
             ResultSet retries = statement.executeQuery("SELECT COUNT(*) FROM liepin_retry_queue")) {
            retries.next();
            assertEquals(0, retries.getInt(1));
        }
    }

    @Test
    void pendingRetrySnapshotIncludesZeroAndNullRowsInStableOrder() {
        assertEquals(List.of(2L, 3L), service.listPendingJobIds());
    }

    @Test
    void suspendedRetrySnapshotExcludesOrdinaryPendingAndDeliveredRows() {
        service.markSuspendedRetry(1L, "AI_TIMEOUT");
        service.markSuspendedRetry(2L, "AI_REQUEST_FAILED");
        service.markSuspendedRetry(3L, "NETWORK_UNCONFIRMED");

        assertEquals(List.of(2L, 3L), service.listSuspendedRetryJobIds());
        assertEquals(new LiepinService.SuspendedRetrySummary(2, 1, 1),
                service.getSuspendedRetrySummary());

        service.clearSuspendedRetry(2L);

        assertEquals(List.of(3L), service.listSuspendedRetryJobIds());
        assertEquals(new LiepinService.SuspendedRetrySummary(1, 0, 1),
                service.getSuspendedRetrySummary());
    }

    @Test
    void cleanupIsIdempotentAfterPendingRowsAreGone() {
        LiepinService.PendingCleanupResult first = service.clearPendingSnapshots();
        LiepinService.PendingCleanupResult second = service.clearPendingSnapshots();

        assertEquals(2, first.deleted());
        assertEquals(new LiepinService.PendingCleanupResult(0, 1, 1, 0), second);
    }
}
