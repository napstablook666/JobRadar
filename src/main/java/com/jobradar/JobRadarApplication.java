package com.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JobRadar 应用程序启动类
 * 自动化求职平台的主入口
 *
 * @author JobRadar
 * @version 0.0.1-SNAPSHOT
 */
@SpringBootApplication(scanBasePackages = "com.jobradar")
@EnableScheduling
@EnableAsync
public class JobRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
