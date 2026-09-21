package com.jobradar.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobradar.application.entity.BlacklistEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Boss黑名单Mapper
 */
@Mapper
public interface BlacklistMapper extends BaseMapper<BlacklistEntity> {
}
