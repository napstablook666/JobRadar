package com.jobradar.application.service;

import com.jobradar.application.mapper.BlacklistMapper;
import com.jobradar.application.mapper.BossConfigMapper;
import com.jobradar.application.mapper.BossIndustryMapper;
import com.jobradar.application.mapper.BossJobDataMapper;
import com.jobradar.application.mapper.BossOptionMapper;
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

class BossServiceSchemaTest {
    private Connection keepAlive;
    private BossService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:boss_schema_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        service = new BossService(
                Mockito.mock(BossOptionMapper.class),
                Mockito.mock(BossIndustryMapper.class),
                Mockito.mock(BossConfigMapper.class),
                Mockito.mock(BlacklistMapper.class),
                Mockito.mock(BossJobDataMapper.class),
                dataSource
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
    }

    @Test
    void createsAllBossTablesInEmptyDatabase() throws Exception {
        service.ensureBossConfigSchema();

        assertTrue(hasTable("boss_config"));
        assertTrue(hasTable("boss_option"));
        assertTrue(hasTable("boss_industry"));
        assertTrue(hasTable("boss_blacklist"));
        assertTrue(hasColumn("boss_config", "enable_ai_screening"));
        assertTrue(hasColumn("boss_option", "sort_order"));
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("INSERT INTO boss_config DEFAULT VALUES");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT debugger, wait_time, enable_ai, send_img_resume, filter_dead_hr FROM boss_config")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("debugger"));
                assertEquals(10, rows.getInt("wait_time"));
                assertEquals(1, rows.getInt("enable_ai"));
                assertEquals(0, rows.getInt("send_img_resume"));
                assertEquals(1, rows.getInt("filter_dead_hr"));
            }
        }
    }

    @Test
    void upgradesLegacyTablesBeforePersistenceUsesNewColumns() throws Exception {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE boss_config (id INTEGER PRIMARY KEY AUTOINCREMENT)");
            statement.execute("CREATE TABLE boss_option (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, name TEXT, code TEXT)");
        }

        service.ensureBossConfigSchema();

        assertTrue(hasTable("boss_industry"));
        assertTrue(hasColumn("boss_config", "enable_ai_screening"));
        assertTrue(hasColumn("boss_config", "ai_min_score"));
        assertTrue(hasColumn("boss_config", "keywords"));
        assertTrue(hasColumn("boss_option", "sort_order"));

        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("INSERT INTO boss_config (enable_ai, enable_ai_screening, ai_min_score) VALUES (1, 1, 85)");
            statement.execute("INSERT INTO boss_option (type, name, code, sort_order) VALUES ('city', '不限', '0', 0)");
        }
        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery("SELECT enable_ai, enable_ai_screening, ai_min_score FROM boss_config LIMIT 1")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt("enable_ai"));
            assertEquals(1, rows.getInt("enable_ai_screening"));
            assertEquals(85, rows.getInt("ai_min_score"));
        }
    }

    private boolean hasTable(String table) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rows.next();
        }
    }

    private boolean hasColumn(String table, String column) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (rows.next()) {
                if (column.equals(rows.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
