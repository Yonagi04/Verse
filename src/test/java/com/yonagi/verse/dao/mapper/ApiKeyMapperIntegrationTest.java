package com.yonagi.verse.dao.mapper;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyMapperIntegrationTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private SqlSession session;
    private ApiKeyMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("CREATE TABLE t_api_key (api_key_id BIGINT PRIMARY KEY, last_used_at TIMESTAMP NULL)");
        jdbcTemplate.update("INSERT INTO t_api_key(api_key_id, last_used_at) VALUES (30, NULL)");

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(database);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(ApiKeyMapper.class);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        session = factory.openSession();
        mapper = session.getMapper(ApiKeyMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void onlyLaterUsageTimeCanReplaceStoredTime() {
        LocalDateTime earlier = LocalDateTime.of(2026, 9, 25, 10, 0);
        LocalDateTime later = earlier.plusMinutes(1);
        assertEquals(1, mapper.updateLastUsedAtIfLater(30L, Timestamp.valueOf(later)));
        assertEquals(0, mapper.updateLastUsedAtIfLater(30L, Timestamp.valueOf(earlier)));
        assertEquals(0, mapper.updateLastUsedAtIfLater(30L, Timestamp.valueOf(later)));
        assertEquals(later, jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM t_api_key WHERE api_key_id = 30", Timestamp.class).toLocalDateTime());
    }
}
