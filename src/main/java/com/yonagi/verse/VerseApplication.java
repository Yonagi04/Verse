package com.yonagi.verse;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@SpringBootConfiguration
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan("com.yonagi.verse.dao.mapper")
public class VerseApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerseApplication.class, args);
    }
}
