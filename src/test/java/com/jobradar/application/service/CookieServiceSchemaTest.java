package com.jobradar.application.service;

import com.jobradar.application.mapper.CookieMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieServiceSchemaTest {
    private Connection keepAlive;
    private CookieService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:cookie_schema_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        service = new CookieService(Mockito.mock(CookieMapper.class), dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void createsCookieTableForDatabaseWithoutSchema() throws Exception {
        service.ensureTableExists();

        assertTrue(hasColumn("id"));
        assertTrue(hasColumn("platform"));
        assertTrue(hasColumn("cookie_value"));
        assertTrue(hasColumn("remark"));
        assertTrue(hasColumn("created_at"));
        assertTrue(hasColumn("updated_at"));
    }

    private boolean hasColumn(String expected) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(cookie)")) {
            while (rows.next()) {
                if (expected.equalsIgnoreCase(rows.getString("name"))) return true;
            }
            return false;
        }
    }
}
