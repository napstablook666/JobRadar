package com.jobradar.application.service;

import com.jobradar.application.mapper.ZhilianConfigMapper;
import com.jobradar.application.mapper.ZhilianJobDataMapper;
import com.jobradar.application.mapper.ZhilianOptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianServiceAiScreeningMigrationTest {

    private Connection keepAlive;
    private ZhilianService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:zhilian_ai_migrate_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE zhilian_config ("
                    + "id INTEGER PRIMARY KEY,"
                    + "keywords TEXT,"
                    + "enable_ai_screening INTEGER,"
                    + "ai_min_score INTEGER,"
                    + "ai_screening_configured INTEGER)");
            statement.execute("INSERT INTO zhilian_config"
                    + "(id, keywords, enable_ai_screening, ai_min_score, ai_screening_configured) VALUES "
                    + "(1, 'closed', 0, 70, 0),"
                    + "(2, 'unset', NULL, 70, 0),"
                    + "(3, 'marked-on', 1, 70, 1)");
        }
        service = new ZhilianService(
                Mockito.mock(ZhilianConfigMapper.class),
                Mockito.mock(ZhilianOptionMapper.class),
                Mockito.mock(ZhilianJobDataMapper.class),
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
    void migratesNullEnableOnlyAndKeepsHistoricalZeroClosed() throws Exception {
        service.ensureZhilianDataTableExists();

        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, enable_ai_screening, ai_screening_configured "
                             + "FROM zhilian_config ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt("id"));
            assertEquals(0, rows.getInt("enable_ai_screening"));
            assertEquals(1, rows.getInt("ai_screening_configured"));

            assertTrue(rows.next());
            assertEquals(2, rows.getInt("id"));
            assertEquals(1, rows.getInt("enable_ai_screening"));
            assertEquals(1, rows.getInt("ai_screening_configured"));

            assertTrue(rows.next());
            assertEquals(3, rows.getInt("id"));
            assertEquals(1, rows.getInt("enable_ai_screening"));
            assertEquals(1, rows.getInt("ai_screening_configured"));
        }
    }
}
