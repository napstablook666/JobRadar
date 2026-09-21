package com.jobradar.application.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper配置
 * 扫描 com.jobradar.application.mapper 包下的所有 Mapper接口
 */
@Configuration
@MapperScan("com.jobradar.application.mapper")
public class DataMapperConfig {
}
