package com.yonagi.verse.service.impl;

import com.yonagi.verse.support.MySqlTestDatabase;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 Mapper 和 Spring 事务代理，验证交换、回滚及竞争，使用隔离 MySQL 测试库。 */
class TenantAdminTransferIntegrationTest {
    private MySqlTestDatabase database;
    private JdbcTemplate jdbc;
    private TenantAdminTransferService service;
    private TenantActivityRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        database = MySqlTestDatabase.create();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE t_tenant (tenant_id BIGINT PRIMARY KEY, owner_id BIGINT, type VARCHAR(20), status INT, del_flag INT)");
        jdbc.execute("CREATE TABLE t_user_tenant (user_id BIGINT, tenant_id BIGINT, role VARCHAR(20), left_at DATETIME(3), PRIMARY KEY(user_id, tenant_id))");
        jdbc.execute("CREATE TABLE t_user (user_id BIGINT PRIMARY KEY, username VARCHAR(50), nickname VARCHAR(50), status INT, del_flag INT)");
        jdbc.update("INSERT INTO t_tenant VALUES (20, 10, 'TEAM', 1, 0), (21, 40, 'TEAM', 1, 0)");
        jdbc.update("INSERT INTO t_user_tenant VALUES (10,20,'SUPER_ADMIN',NULL),(30,20,'ADMIN',NULL),(31,20,'ADMIN',NULL),(32,20,'MEMBER',NULL),(40,21,'SUPER_ADMIN',NULL)");
        jdbc.update("INSERT INTO t_user VALUES (10,'owner','原超管',1,0),(30,'admin','新超管',1,0),(31,'other','另一管理员',1,0),(32,'member','成员',1,0),(40,'outsider','外部超管',1,0)");
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TenantMapper.class);
        config.addMapper(UserTenantMapper.class);
        config.addMapper(UserMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(database);
        factory.setConfiguration(config);
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        recorder = mock(TenantActivityRecorder.class);
        TenantAdminTransferService target = new TenantAdminTransferService(template.getMapper(TenantMapper.class),
                template.getMapper(UserTenantMapper.class), template.getMapper(UserMapper.class), recorder);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database), new AnnotationTransactionAttributeSource()));
        service = (TenantAdminTransferService) proxy.getProxy();
    }

    @AfterEach
    void tearDown() { if (database != null) database.close(); }

    @Test
    void transfersBothRolesAndOwnershipWithoutTouchingOtherMembersOrTenants() {
        assertTrue(service.transfer(10L, 20L, 30L));
        assertEquals("ADMIN", role(10));
        assertEquals("SUPER_ADMIN", role(30));
        assertEquals(30L, owner());
        assertEquals("ADMIN", role(31));
        assertEquals(40L, jdbc.queryForObject("SELECT owner_id FROM t_tenant WHERE tenant_id=21", Long.class));
        verify(recorder, times(2)).record(eq(20L), any(TenantActivityDraft.class));
    }

    @Test
    void rejectsMemberMissingLeftClosedAndCrossTenantTargets() {
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,32L));
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,99L));
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,40L));
        jdbc.update("UPDATE t_user_tenant SET left_at=CURRENT_TIMESTAMP WHERE user_id=30");
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,30L));
        jdbc.update("UPDATE t_user SET status=2, del_flag=1 WHERE user_id=31");
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,31L));
        assertUnchanged();
        verifyNoInteractions(recorder);
    }

    @Test
    void rejectsAdminMemberOutsiderSelfAndInvalidIds() {
        for (long operator : new long[]{30,32,40,99}) {
            assertThrows(ClientException.class, () -> service.transfer(operator,20L,31L));
        }
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,10L));
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,null));
        assertUnchanged();
    }

    @Test
    void rejectsPersonalDisabledMissingAndStaleOwner() {
        jdbc.update("UPDATE t_tenant SET type='PERSONAL' WHERE tenant_id=20");
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,30L));
        jdbc.update("UPDATE t_tenant SET type='TEAM', status=0 WHERE tenant_id=20");
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,30L));
        assertThrows(ClientException.class, () -> service.transfer(10L,99L,30L));
        jdbc.update("UPDATE t_tenant SET status=1, owner_id=31 WHERE tenant_id=20");
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,30L));
        assertEquals("SUPER_ADMIN", role(10));
    }

    @Test
    void repeatedRequestCannotCreateAnotherSuperAdminAndNewOwnerCanTransferBack() {
        service.transfer(10L,20L,30L);
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,31L));
        assertThrows(ClientException.class, () -> service.transfer(10L,20L,30L));
        assertTrue(service.transfer(30L,20L,10L));
        assertUnchanged();
    }

    @Test
    void outboxFailureRollsBackBothRolesAndOwner() {
        when(recorder.record(eq(20L), any())).thenThrow(new IllegalStateException("outbox unavailable"));
        assertThrows(IllegalStateException.class, () -> service.transfer(10L,20L,30L));
        assertUnchanged();
    }

    @Test
    void ownerWriteFailureRollsBackTheRoleSwap() {
        jdbc.execute("ALTER TABLE t_tenant ADD CONSTRAINT owner_write_failure CHECK(owner_id<>30)");
        assertThrows(RuntimeException.class, () -> service.transfer(10L,20L,30L));
        assertUnchanged();
        verifyNoInteractions(recorder);
    }

    @Test
    void concurrentTransfersHaveExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(start,30L));
            var second = executor.submit(() -> attempt(start,31L));
            start.countDown();
            int winners = first.get(10,TimeUnit.SECONDS) + second.get(10,TimeUnit.SECONDS);
            assertEquals(1,winners);
        }
        assertEquals("ADMIN", role(10));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_user_tenant WHERE tenant_id=20 AND role='SUPER_ADMIN'", Integer.class));
        assertEquals("SUPER_ADMIN", role(owner()));
    }

    private int attempt(CountDownLatch start, Long target) throws Exception {
        start.await();
        try { service.transfer(10L,20L,target); return 1; }
        catch (ClientException expected) { return 0; }
    }

    private String role(long userId) {
        return jdbc.queryForObject("SELECT role FROM t_user_tenant WHERE tenant_id=20 AND user_id=?", String.class,userId);
    }

    private long owner() {
        return jdbc.queryForObject("SELECT owner_id FROM t_tenant WHERE tenant_id=20", Long.class);
    }

    private void assertUnchanged() {
        assertEquals("SUPER_ADMIN",role(10));
        assertEquals("ADMIN",role(30));
        assertEquals(10L,owner());
    }
}
