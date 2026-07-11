package com.yonagi.verse;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@SpringBootConfiguration
@MapperScan("com.yonagi.verse.dao.mapper")
public class VerseApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerseApplication.class, args);
    }
}