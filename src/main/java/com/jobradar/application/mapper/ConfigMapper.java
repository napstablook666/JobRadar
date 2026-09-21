package com.jobradar.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobradar.application.entity.ConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 配置Mapper接口
 */
@Mapper
public interface ConfigMapper extends BaseMapper<ConfigEntity> {
}
