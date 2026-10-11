package com.yonagi.verse.async.outbox;

import com.yonagi.verse.support.MySqlTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 清理只匹配两类个人动态，覆盖所有投递状态、其他事件隔离与重复执行。 */
class CostBudgetActivityCleanupIntegrationTest {
    @Test void removesOnlyRetiredActivityFactsAndOutboxRowsIdempotently() throws Exception {
        try (var database = MySqlTestDatabase.create(); var connection = database.getConnection()) {
            String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
            for (String table : List.of("t_domain_event_outbox", "t_tenant_activity_log")) {
                var definition = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?;").matcher(schema);
                assertTrue(definition.find());
                ScriptUtils.executeSqlScript(connection,
                        new ByteArrayResource(definition.group().getBytes(StandardCharsets.UTF_8)));
            }
            JdbcTemplate jdbc = new JdbcTemplate(database);
            for (String type : List.of("API_KEY_COST_CONFIGURED", "API_KEY_COST_REJECTED")) {
                jdbc.update("""
                        INSERT INTO t_tenant_activity_log
                            (event_id, tenant_id, category, activity_type, actor_user_id, actor_username,
                             detail_json, schema_version, occurred_at)
                        VALUES (?, 2, 'TENANT', ?, 1, 'alice', '{}', 1, NOW(3))
                        """, type, type);
                for (String status : List.of("PENDING", "CLAIMED", "RETRY", "FAILED", "PUBLISHED")) {
                    insertOutbox(jdbc, type + status, "TENANT_ACTIVITY", type, status);
                }
            }
            jdbc.update("""
                    INSERT INTO t_tenant_activity_log
                        (event_id, tenant_id, category, activity_type, actor_user_id, actor_username,
                         detail_json, schema_version, occurred_at)
                    VALUES ('kept', 3, 'TENANT', 'TENANT_SETTINGS_UPDATED', 1, 'alice', '{}', 1, NOW(3))
                    """);
            insertOutbox(jdbc, "kept-activity", "TENANT_ACTIVITY", "TENANT_SETTINGS_UPDATED", "PENDING");
            insertOutbox(jdbc, "kept-other-tag", "USER_CLOSED", "API_KEY_COST_REJECTED", "PENDING");
            insertOutbox(jdbc, "kept-missing-type", "TENANT_ACTIVITY", null, "PENDING");

            var cleanup = new FileSystemResource("scripts/sql/V20261011_01__remove_api_key_cost_activities.sql");
            for (int attempt = 0; attempt < 2; attempt++) {
                ScriptUtils.executeSqlScript(connection, cleanup);
                assertEquals(List.of("kept"), jdbc.queryForList(
                        "SELECT event_id FROM t_tenant_activity_log ORDER BY event_id", String.class));
                assertEquals(List.of("kept-activity", "kept-missing-type", "kept-other-tag"), jdbc.queryForList(
                        "SELECT event_id FROM t_domain_event_outbox ORDER BY event_id", String.class));
            }
        }
    }

    private void insertOutbox(JdbcTemplate jdbc, String id, String tag, String type, String status) {
        String payload = type == null ? "{}" : "{\"activityType\":\"" + type + "\"}";
        jdbc.update("""
                INSERT INTO t_domain_event_outbox
                    (event_id, tenant_id, event_type, message_key, payload_json, status, next_retry_at)
                VALUES (?, 2, ?, '2', ?, ?, NOW(3))
                """, id, tag, payload, status);
    }
}
