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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiepinServicePageProgressTest {

    private Connection keepAlive;
    private SQLiteDataSource dataSource;
    private LiepinService service;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:liepin_progress_"
                + UUID.randomUUID().toString().replace("-", "")
                + "?mode=memory&cache=shared");
        keepAlive = dataSource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE liepin_page_progress ("
                    + "keyword TEXT NOT NULL, city_code TEXT NOT NULL DEFAULT '', "
                    + "salary_code TEXT NOT NULL DEFAULT '', last_completed_page INTEGER NOT NULL, "
                    + "updated_at DATETIME, PRIMARY KEY(keyword, city_code, salary_code))");
        }
        service = newService();
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
    }

    @Test
    void progressSurvivesServiceRecreationAndUpdatesInPlace() {
        service.saveLastCompletedPage(" 放疗物理师 ", "410", "6$10", 2);

        LiepinService reloaded = newService();

        assertEquals(2, reloaded.getLastCompletedPage("放疗物理师", "410", "6$10"));
        reloaded.saveLastCompletedPage("放疗物理师", "410", "6$10", 4);
        assertEquals(4, service.getLastCompletedPage("放疗物理师", "410", "6$10"));
    }

    @Test
    void progressIsIsolatedBySearchFiltersAndClearsOnlyOneKey() {
        service.saveLastCompletedPage("放疗物理师", "410", "6$10", 2);
        service.saveLastCompletedPage("放疗物理师", "310", "6$10", 4);

        service.clearPageProgress("放疗物理师", "410", "6$10");

        assertNull(service.getLastCompletedPage("放疗物理师", "410", "6$10"));
        assertEquals(4, service.getLastCompletedPage("放疗物理师", "310", "6$10"));
    }

    private LiepinService newService() {
        return new LiepinService(
                Mockito.mock(LiepinConfigMapper.class),
                Mockito.mock(LiepinOptionMapper.class),
                Mockito.mock(LiepinMapper.class),
                dataSource
        );
    }
}
