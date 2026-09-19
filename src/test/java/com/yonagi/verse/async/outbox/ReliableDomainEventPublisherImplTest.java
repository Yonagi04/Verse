package com.yonagi.verse.async.outbox;

import com.yonagi.verse.async.event.TenantActivityEvent;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReliableDomainEventPublisherImplTest {
    @Test void publisherDeclaresMandatoryTransactionAndPayloadHasNoCredential() throws Exception {
        Transactional tx=ReliableDomainEventPublisherImpl.class.getMethod("publish",com.yonagi.verse.async.api.DomainEvent.class,Long.class)
                .getAnnotation(Transactional.class); assertNotNull(tx); assertEquals(Propagation.MANDATORY,tx.propagation());
        DomainEventOutboxMapper mapper=mock(DomainEventOutboxMapper.class); ReliableDomainEventPublisherImpl publisher=
                new ReliableDomainEventPublisherImpl(mapper,mock(DomainOutboxMetrics.class));
        TenantActivityEvent event=new TenantActivityEvent(); event.setTenantId(20L); event.setKey("20");
        event.setCategory(TenantActivityCategory.LLM_SERVICE); event.setActivityType(TenantActivityType.LLM_SERVICE_CREATED);
        event.setActorUserId(10L); event.setActorUsername("alice"); event.setDetails(java.util.Map.of("provider","OpenAI"));
        publisher.publish(event,20L); ArgumentCaptor<DomainEventOutboxDO> captor=ArgumentCaptor.forClass(DomainEventOutboxDO.class);
        verify(mapper).insert(captor.capture()); String payload=captor.getValue().getPayloadJson();
        assertFalse(payload.contains("sk_secret")); assertEquals("PENDING",captor.getValue().getStatus());
    }
}
