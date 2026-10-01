package com.yonagi.verse.dao.mapper;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SQL 检查租户锁、并发终态释放条件及迁移唯一约束。 */
class PlaygroundWorkspaceMapperIntegrationTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private SqlSession session;
    private PlaygroundWorkspaceMapper mapper;
    private PlaygroundAttemptMapper attemptMapper;

    @BeforeEach void setup() throws Exception {
        database = new EmbeddedDatabaseBuilder().setName("workbench" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1").setType(EmbeddedDatabaseType.H2).build();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261001_01__playground_workbench.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        jdbc.update("INSERT INTO t_playground_workspace(workspace_id,tenant_id,owner_user_id,kind,title,payload,generating,create_time,update_time) VALUES(11,2,7,'GROUP','test','{}',1,NOW(),NOW())");
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(database);
        var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true); config.addMapper(PlaygroundWorkspaceMapper.class); config.addMapper(PlaygroundAttemptMapper.class); factory.setConfiguration(config);
        session = factory.getObject().openSession(true); mapper = session.getMapper(PlaygroundWorkspaceMapper.class);
        attemptMapper = session.getMapper(PlaygroundAttemptMapper.class);
    }
    @AfterEach void close() { if (session != null) session.close(); if (database != null) database.shutdown(); }
    private void insert(int id, String status, String lane) {
        jdbc.update("INSERT INTO t_playground_attempt(attempt_id,tenant_id,owner_user_id,workspace_id,round_id,round_no,lane_id,attempt_no,request_id,service_id,prompt,status,snapshot,create_time,update_time) VALUES(?,2,7,11,'round',1,?,1,?,9,'prompt',?,'{}',NOW(),NOW())", id, lane, "request" + id, status);
    }
    @Test void lockIsOwnerScopedAndGroupWaitsForEveryLaneIncludingStopInProgress() {
        assertNull(mapper.lock(3L, 7L, 11L)); assertNull(mapper.lock(2L, 8L, 11L)); assertNotNull(mapper.lock(2L, 7L, 11L));
        insert(21, "COMPLETED", "A"); insert(22, "STOPPING", "B");
        assertEquals(0, mapper.release(11L));
        jdbc.update("UPDATE t_playground_attempt SET status='STOPPED' WHERE attempt_id=22");
        assertEquals(1, mapper.release(11L)); assertEquals(0, jdbc.queryForObject("SELECT generating FROM t_playground_workspace WHERE workspace_id=11", Integer.class));
    }
    @Test void duplicateAttemptsCannotOverwriteOriginalResult() {
        insert(21, "COMPLETED", "A"); assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> insert(22, "PENDING", "A"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_playground_attempt", Integer.class));
    }
    @Test void pendingStopIsAtomicAndCannotOverwriteAlreadyClaimedStream() {
        insert(21, "PENDING", "A"); insert(22, "STREAMING", "B");
        assertEquals(0, attemptMapper.stopPending(2L, 8L, 21L));
        assertEquals(1, attemptMapper.stopPending(2L, 7L, 21L));
        assertEquals(0, attemptMapper.stopPending(2L, 7L, 21L));
        assertEquals(0, attemptMapper.stopPending(2L, 7L, 22L));
        assertEquals("STREAMING", jdbc.queryForObject("SELECT status FROM t_playground_attempt WHERE attempt_id=22", String.class));
        assertEquals(0, mapper.release(11L));
    }
}
