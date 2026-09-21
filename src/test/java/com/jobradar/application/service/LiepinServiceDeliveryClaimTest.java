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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinServiceDeliveryClaimTest {

    private Connection keepAlive;
    private LiepinService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:liepin_delivery_claim_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE liepin_data (job_id INTEGER PRIMARY KEY, delivered INTEGER)");
            statement.execute("CREATE TABLE liepin_delivery_claim (claim_key TEXT PRIMARY KEY, job_id INTEGER NOT NULL, claimed_at DATETIME NOT NULL)");
            statement.execute("INSERT INTO liepin_data(job_id, delivered) VALUES (1, 0), (2, 0), (3, 1)");
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
    void onlyOnePendingPositionForTheSameRecruiterCanClaimDelivery() {
        assertTrue(service.tryClaimDelivery(1L, "im-42", "hr-42"));
        assertFalse(service.tryClaimDelivery(2L, "im-42", "hr-42"));
        assertTrue(service.tryClaimDelivery(2L, "im-43", "hr-43"));
    }

    @Test
    void deliveredOrAlreadyClaimedPositionsCannotBeClaimedAgain() {
        assertTrue(service.tryClaimDelivery(1L, null, "hr-1"));
        assertFalse(service.tryClaimDelivery(1L, null, "hr-1"));
        assertFalse(service.tryClaimDelivery(3L, null, "hr-3"));
    }
}
