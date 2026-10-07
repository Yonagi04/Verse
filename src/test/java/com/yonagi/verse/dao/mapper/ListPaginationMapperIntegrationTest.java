package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yonagi.verse.common.config.MyBatisPlusConfig;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO.LlmServiceInfo;
import com.yonagi.verse.support.MySqlTestDatabase;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListPaginationMapperIntegrationTest {
    private MySqlTestDatabase database;
    private SqlSession session;
    private JdbcTemplate jdbc;
    private LlmServiceMapper models;
    private TenantInviteMapper invites;

    @BeforeEach
    void setUp() throws Exception {
        database = MySqlTestDatabase.create();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE t_user(user_id BIGINT PRIMARY KEY,username VARCHAR(64))");
        jdbc.execute("CREATE TABLE t_llm_service(service_id BIGINT PRIMARY KEY,tenant_id BIGINT,name VARCHAR(128),"
                + "model_name VARCHAR(128),provider VARCHAR(32),description VARCHAR(255),status INT,context_window BIGINT,"
                + "max_output_tokens BIGINT,created_by BIGINT,active_pricing_id BIGINT,del_flag INT,create_time DATETIME(3),"
                + "KEY idx_tenant_id(tenant_id))");
        jdbc.execute("CREATE TABLE t_llm_service_pricing(pricing_id BIGINT PRIMARY KEY,billing_mode VARCHAR(32),currency VARCHAR(8))");
        jdbc.execute("CREATE TABLE t_llm_service_tag(service_id BIGINT,tag_code VARCHAR(32),"
                + "UNIQUE KEY uk_service_tag(service_id,tag_code),KEY idx_tag_code(tag_code,service_id))");
        jdbc.execute("CREATE TABLE t_tenant_invite(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(64),created_by BIGINT,"
                + "usage_count INT,is_active INT,expires_at DATETIME(3),create_time DATETIME(3))");
        jdbc.update("INSERT INTO t_user VALUES(1,'owner')");
        jdbc.update("INSERT INTO t_llm_service_pricing VALUES(1,'TOKEN','CNY')");
        for (int id = 1; id <= 7; id++) {
            jdbc.update("INSERT INTO t_llm_service VALUES(?, ?, ?, 'upstream-model', ?, '说明', 1, 8192, 2048, ?, ?, ?, '2026-10-01')",
                    id, id == 5 ? 3 : 2, id == 1 ? "literal%_name" : "Model-" + id,
                    id <= 2 ? "GeMiNi" : "openai", id == 7 ? 999 : 1, id == 1 ? 1 : null, id == 6 ? 1 : 0);
        }
        jdbc.update("INSERT INTO t_llm_service_tag VALUES(1,'chat'),(1,'vision'),(2,'chat'),(3,'vision'),(5,'chat'),(6,'chat')");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database);
        var configuration = new MybatisConfiguration();
        configuration.addMapper(LlmServiceMapper.class);
        configuration.addMapper(TenantInviteMapper.class);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MyBatisPlusConfig().mybatisPlusInterceptor());
        session = factory.getObject().openSession(true);
        models = session.getMapper(LlmServiceMapper.class);
        invites = session.getMapper(TenantInviteMapper.class);
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        if (database != null) database.close();
    }

    private Page<LlmServiceInfo> page(int number, int size, String keyword, List<String> providers, List<String> tags) {
        var page = new Page<LlmServiceInfo>(number, size);
        page.setOptimizeJoinOfCountSql(false);
        return models.selectPageByTenantId(page, 2L, keyword, providers, tags.isEmpty() ? null : tags);
    }

    @Test
    void filtersBeforePaginationWithoutDuplicatingMultiTaggedModels() {
        var first = page(1, 2, null, List.of(), List.of("chat", "vision"));
        assertEquals(3, first.getTotal());
        assertEquals(2, first.getPages());
        assertEquals(List.of(3L, 2L), first.getRecords().stream().map(LlmServiceInfo::getServiceId).toList());
        var second = page(2, 2, null, List.of(), List.of("chat", "vision"));
        assertEquals(List.of(1L), second.getRecords().stream().map(LlmServiceInfo::getServiceId).toList());
        assertEquals("TOKEN", second.getRecords().getFirst().getBillingStatus());
        assertEquals("CNY", second.getRecords().getFirst().getCurrency());
        assertEquals("owner", second.getRecords().getFirst().getCreatedByUsername());
        assertEquals("upstream-model", second.getRecords().getFirst().getModelName());
        assertEquals(8192L, second.getRecords().getFirst().getContextWindow());
        assertEquals(4, models.countByTenantId(2L));
    }

    @Test
    void preservesLiteralCaseInsensitiveKeywordAndProviderAliasSearch() {
        assertEquals(1, page(1, 10, "%_", List.of(), List.of()).getTotal());
        assertEquals(1, page(1, 10, "mOdEl-3", List.of(), List.of()).getTotal());
        assertEquals(2, page(1, 10, "谷歌", List.of("gemini"), List.of()).getTotal());
        assertEquals(1, page(1, 10, "谷歌", List.of("gemini"), List.of("vision")).getTotal());
        assertEquals(0, page(1, 10, "absent", List.of(), List.of()).getTotal());
        assertEquals(0, models.selectPageByTenantId(new Page<>(1, 10), 2L, null, List.of(), List.of()).getTotal());
        var beyond = page(Integer.MAX_VALUE, 100, null, List.of(), List.of());
        assertEquals(4, beyond.getTotal());
        assertTrue(beyond.getRecords().isEmpty());
    }

    @Test
    void invitationExpiryChangesPageAndTotalWithoutLoadingCandidates() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 7, 12, 0);
        jdbc.update("INSERT INTO t_tenant_invite VALUES(1,2,'first',1,0,1,?,'2026-10-01'),"
                + "(2,2,'permanent',1,0,0,NULL,'2026-10-01'),(3,3,'other-tenant',1,0,1,NULL,'2026-10-01')",
                Timestamp.valueOf(start.plusSeconds(1)));
        var before = invites.selectPageByTenantId(new Page<>(2, 1), 2L, Timestamp.valueOf(start));
        assertEquals(2, before.getTotal());
        assertEquals("first", before.getRecords().getFirst().getCode());
        session.clearCache();
        var after = invites.selectPageByTenantId(new Page<>(2, 1), 2L, Timestamp.valueOf(start.plusSeconds(1)));
        assertEquals(1, after.getTotal());
        assertTrue(after.getRecords().isEmpty());
    }
}
