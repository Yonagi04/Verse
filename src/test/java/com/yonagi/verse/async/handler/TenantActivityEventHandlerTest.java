package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.event.TenantActivityEvent;
import com.yonagi.verse.async.outbox.DomainOutboxMetrics;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import com.yonagi.verse.dao.mapper.TenantActivityLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantActivityEventHandlerTest {
    @Test void persistsImmutableSnapshotAndOriginalOccurredAt() {
        TenantActivityLogMapper mapper=mock(TenantActivityLogMapper.class); DomainOutboxMetrics metrics=mock(DomainOutboxMetrics.class);
        TenantActivityEventHandler handler=new TenantActivityEventHandler(mapper,metrics); TenantActivityEvent event=event();
        event.setOccurredAt(1_700_000_000_123L); handler.onEvent(event);
        ArgumentCaptor<TenantActivityLogDO> captor=ArgumentCaptor.forClass(TenantActivityLogDO.class); verify(mapper).insert(captor.capture());
        TenantActivityLogDO row=captor.getValue(); assertEquals("alice",row.getActorUsername()); assertEquals("Service A",row.getTargetName());
        assertEquals(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(event.getOccurredAt()),java.time.ZoneId.of("Asia/Shanghai")),row.getOccurredAt());
    }

    @Test void confirmedDuplicateSucceedsAndUnknownDuplicateEscalates() {
        TenantActivityLogMapper mapper=mock(TenantActivityLogMapper.class); TenantActivityEvent event=event();
        doThrow(new DuplicateKeyException("dup")).when(mapper).insert(any()); when(mapper.countByEventId(event.getEventId())).thenReturn(1L);
        assertDoesNotThrow(()->new TenantActivityEventHandler(mapper,mock(DomainOutboxMetrics.class)).onEvent(event));
        when(mapper.countByEventId(event.getEventId())).thenReturn(0L);
        assertThrows(DuplicateKeyException.class,()->new TenantActivityEventHandler(mapper,mock(DomainOutboxMetrics.class)).onEvent(event));
    }

    @Test void rejectsUnsupportedVersionAndMissingFields() {
        TenantActivityEvent unsupported=event(); unsupported.setSchemaVersion(2);
        assertThrows(IllegalArgumentException.class,()->new TenantActivityEventHandler(mock(TenantActivityLogMapper.class),mock(DomainOutboxMetrics.class)).onEvent(unsupported));
        TenantActivityEvent invalid=event(); invalid.setActorUsername(null);
        assertThrows(IllegalArgumentException.class,()->new TenantActivityEventHandler(mock(TenantActivityLogMapper.class),mock(DomainOutboxMetrics.class)).onEvent(invalid));
    }

    private TenantActivityEvent event(){
        TenantActivityEvent e=new TenantActivityEvent(); e.setTenantId(20L); e.setKey("20"); e.setCategory(TenantActivityCategory.LLM_SERVICE);
        e.setActivityType(TenantActivityType.LLM_SERVICE_CREATED); e.setActorUserId(10L); e.setActorUsername("alice"); e.setActorNickname("小艾");
        e.setTargetType(TenantActivityTargetType.LLM_SERVICE); e.setTargetId("30"); e.setTargetName("Service A");
        e.setDetails(java.util.Map.of("provider","openai","modelName","gpt")); return e;
    }
}
