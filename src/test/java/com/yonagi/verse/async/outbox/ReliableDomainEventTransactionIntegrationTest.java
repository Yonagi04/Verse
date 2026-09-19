package com.yonagi.verse.async.outbox;

import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.TenantActivityEvent;
import com.yonagi.verse.common.enums.TenantActivityCategory;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReliableDomainEventTransactionIntegrationTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private DomainEventOutboxMapper mapper;
    private ReliableDomainEventPublisher publisher;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("CREATE TABLE business_record (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE event_outbox (event_id VARCHAR(64) PRIMARY KEY, tenant_id BIGINT NOT NULL)");

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(database);
        transactionTemplate = new TransactionTemplate(transactionManager);
        mapper = mock(DomainEventOutboxMapper.class);
        when(mapper.insert(any(DomainEventOutboxDO.class))).thenAnswer(invocation -> {
            DomainEventOutboxDO row = invocation.getArgument(0);
            return jdbcTemplate.update("INSERT INTO event_outbox(event_id, tenant_id) VALUES (?, ?)",
                    row.getEventId(), row.getTenantId());
        });

        ReliableDomainEventPublisherImpl target = new ReliableDomainEventPublisherImpl(
                mapper, mock(DomainOutboxMetrics.class));
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        publisher = (ReliableDomainEventPublisher) proxyFactory.getProxy();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void mandatoryPublisherRejectsCallsWithoutBusinessTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> publisher.publish(event(), 20L));
    }

    @Test
    void businessAndOutboxCommitAndRollbackAtomically() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_record(id) VALUES (1)");
            publisher.publish(event(), 20L);
        });
        assertEquals(1, count("business_record"));
        assertEquals(1, count("event_outbox"));

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_record(id) VALUES (2)");
            publisher.publish(event(), 20L);
            throw new IllegalStateException("rollback business operation");
        }));
        assertEquals(1, count("business_record"));
        assertEquals(1, count("event_outbox"));

        when(mapper.insert(any(DomainEventOutboxDO.class)))
                .thenThrow(new IllegalStateException("outbox insert failed"));
        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO business_record(id) VALUES (3)");
            publisher.publish(event(), 20L);
        }));
        assertEquals(1, count("business_record"));
        assertEquals(1, count("event_outbox"));
    }

    private int count(String table) throws DataAccessException {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private TenantActivityEvent event() {
        TenantActivityEvent event = new TenantActivityEvent();
        event.setTenantId(20L);
        event.setKey("20");
        event.setCategory(TenantActivityCategory.TENANT);
        event.setActivityType(TenantActivityType.TENANT_SETTINGS_UPDATED);
        event.setActorUserId(10L);
        event.setActorUsername("alice");
        return event;
    }
}
