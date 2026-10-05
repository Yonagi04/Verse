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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        jdbcTemplate.execute("CREATE TABLE t_api_key (api_key_id BIGINT PRIMARY KEY, user_id BIGINT, tenant_id BIGINT, "
                + "api_key VARCHAR(64), status INT, expires_at TIMESTAMP, rate_limit_rpm INT, rate_limit_tpm INT, last_used_at TIMESTAMP NULL)");
        jdbcTemplate.execute("CREATE TABLE t_user(user_id BIGINT PRIMARY KEY,status INT,del_flag INT)");
        jdbcTemplate.execute("CREATE TABLE t_tenant(tenant_id BIGINT PRIMARY KEY,status INT,del_flag INT)");
        jdbcTemplate.execute("CREATE TABLE t_user_tenant(user_id BIGINT,tenant_id BIGINT,left_at TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO t_api_key(api_key_id,user_id,tenant_id,api_key,status) VALUES (30,10,20,'hash',1)");
        jdbcTemplate.update("INSERT INTO t_user VALUES (10,1,0)");
        jdbcTemplate.update("INSERT INTO t_tenant VALUES (20,1,0)");
        jdbcTemplate.update("INSERT INTO t_user_tenant VALUES (10,20,NULL)");

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

    @Test
    void closedUserCannotAuthenticateBeforeAsynchronousKeyRevocation() {
        assertNotNull(mapper.selectAuthState(30L, "hash"));
        jdbcTemplate.update("UPDATE t_user SET status=2,del_flag=1 WHERE user_id=10");
        session.clearCache();
        assertNull(mapper.selectAuthState(30L, "hash"));
    }

    @Test
    void leftMembershipAndDeletedTenantCannotAuthenticate() {
        jdbcTemplate.update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP WHERE user_id=10");
        assertNull(mapper.selectAuthState(30L, "hash"));
        jdbcTemplate.update("UPDATE t_user_tenant SET left_at=NULL WHERE user_id=10");
        jdbcTemplate.update("UPDATE t_tenant SET status=0,del_flag=1 WHERE tenant_id=20");
        session.clearCache();
        assertNull(mapper.selectAuthState(30L, "hash"));
    }
}
