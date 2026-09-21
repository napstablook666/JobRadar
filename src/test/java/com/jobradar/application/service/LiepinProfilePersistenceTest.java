package com.jobradar.application.service;

import com.jobradar.application.entity.LiepinConfigEntity;
import com.jobradar.application.mapper.LiepinConfigMapper;
import com.jobradar.application.mapper.LiepinMapper;
import com.jobradar.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiepinProfilePersistenceTest {
    @Test
    void insertAndSelectiveUpdateKeepExplicitSearchProfile() {
        LiepinConfigMapper mapper = mock(LiepinConfigMapper.class);
        LiepinService service = new LiepinService(
                mapper,
                mock(LiepinOptionMapper.class),
                mock(LiepinMapper.class),
                mock(javax.sql.DataSource.class)
        );

        LiepinConfigEntity inserted = new LiepinConfigEntity();
        inserted.setSearchProfile("B");
        inserted.setKeywords("病理 IVD\n医疗运营");
        when(mapper.selectOne(any())).thenReturn(null);
        service.saveOrUpdateFirstSelective(inserted);
        assertEquals("B", inserted.getSearchProfile());
        verify(mapper).insert(inserted);

        LiepinConfigEntity existing = new LiepinConfigEntity();
        existing.setId(7L);
        existing.setSearchProfile("A");
        existing.setKeywords("[]");
        when(mapper.selectOne(any())).thenReturn(existing);

        LiepinConfigEntity update = new LiepinConfigEntity();
        update.setSearchProfile("B");
        update.setKeywords(List.of("病理 IVD" ).toString());
        LiepinConfigEntity saved = service.saveOrUpdateFirstSelective(update);

        assertEquals("B", saved.getSearchProfile());
        verify(mapper).updateById(existing);
    }
}
