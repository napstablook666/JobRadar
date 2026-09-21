package com.jobradar.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jobradar.application.entity.LiepinEntity;
import com.jobradar.application.mapper.LiepinConfigMapper;
import com.jobradar.application.mapper.LiepinMapper;
import com.jobradar.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinServiceSnapshotTest {

    @Test
    void batchSnapshotReportsInsertedAndExistingRows() throws Exception {
        LiepinMapper mapper = mock(LiepinMapper.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(entity(1L)));
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        LiepinService service = new LiepinService(
                mock(LiepinConfigMapper.class),
                mock(LiepinOptionMapper.class),
                mapper,
                dataSource
        );

        LiepinService.SnapshotPersistResult result = service.insertSnapshotsIfNotExistsBatch(
                List.of(entity(1L), entity(2L))
        );

        assertEquals(2, result.fetched());
        assertEquals(1, result.inserted());
        assertEquals(1, result.existing());
        assertTrue(result.writeSucceeded());
        verify(statement).executeBatch();
        verify(connection).commit();
    }

    private static LiepinEntity entity(long jobId) {
        LiepinEntity entity = new LiepinEntity();
        entity.setJobId(jobId);
        entity.setJobTitle("设备应用工程师");
        return entity;
    }
}
