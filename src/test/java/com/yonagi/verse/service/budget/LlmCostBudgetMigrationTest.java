package com.yonagi.verse.service.budget;

import com.yonagi.verse.support.MySqlTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

/** 以一期表结构验证显式升级保留 Key 幂等标志及 USER 动态身份。 */
class LlmCostBudgetMigrationTest {
    @Test void upgradePreservesExistingAccountingAndInitializesModelsClosed() throws Exception {
        try (var database = MySqlTestDatabase.create(); var connection = database.getConnection()) {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE t_llm_service(id BIGINT PRIMARY KEY,tenant_id BIGINT,service_id BIGINT)");
            jdbc.execute("CREATE TABLE t_cost_budget_invocation(id BIGINT PRIMARY KEY,api_key_id BIGINT NOT NULL)");
            jdbc.execute("CREATE TABLE t_cost_budget_settlement(id BIGINT PRIMARY KEY,tenant_id BIGINT,service_id BIGINT,request_started_at DATETIME(6),api_key_id BIGINT NOT NULL,budget_applied TINYINT NOT NULL)");
            jdbc.execute("CREATE TABLE t_tenant_activity_log(id BIGINT PRIMARY KEY,actor_user_id BIGINT NOT NULL)");
            jdbc.update("INSERT INTO t_llm_service VALUES(1,2,10)");
            jdbc.update("INSERT INTO t_cost_budget_invocation VALUES(1,3)");
            jdbc.update("INSERT INTO t_cost_budget_settlement VALUES(1,2,10,NOW(6),3,1)");
            jdbc.update("INSERT INTO t_tenant_activity_log VALUES(1,42)");
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("scripts/sql/V20261011_02__llm_cost_budget.sql"));
            assertEquals(1,jdbc.queryForObject("SELECT budget_applied FROM t_cost_budget_settlement",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_cost_budget_settlement' AND column_name='llm_budget_applied'",Integer.class));
            assertEquals("API_KEY",jdbc.queryForObject("SELECT source FROM t_cost_budget_invocation",String.class));
            assertEquals("USER",jdbc.queryForObject("SELECT actor_type FROM t_tenant_activity_log",String.class));
            assertEquals(42L,jdbc.queryForObject("SELECT actor_user_id FROM t_tenant_activity_log",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_llm_cost_budget' AND column_name='cost_data_state'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT cost_limit_enabled FROM t_llm_cost_budget",Integer.class));
            jdbc.update("INSERT INTO t_cost_budget_invocation(id,api_key_id,source) VALUES(2,NULL,'PLAYGROUND')");
            jdbc.update("INSERT INTO t_cost_budget_settlement(id,api_key_id,budget_applied) VALUES(2,NULL,1)");
            jdbc.update("INSERT INTO t_tenant_activity_log(id,actor_user_id,actor_type) VALUES(2,NULL,'SYSTEM')");
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM t_cost_budget_invocation",Integer.class));
        }
    }
}
