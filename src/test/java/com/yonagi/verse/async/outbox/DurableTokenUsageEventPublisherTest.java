package com.yonagi.verse.async.outbox;

import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.UsageOutboxStatus;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableTokenUsageEventPublisherTest {
    @Test
    void stagesStableEventInPendingState() {
        TokenUsageOutboxMapper mapper = mock(TokenUsageOutboxMapper.class);
        UsageOutboxMetrics metrics = mock(UsageOutboxMetrics.class);
        UsageOutboxProperties properties = new UsageOutboxProperties();
        DurableTokenUsageEventPublisher publisher = new DurableTokenUsageEventPublisher(mapper, properties, metrics);
        TokenUsageEvent event = new TokenUsageEvent();
        event.setTenantId(7L);

        publisher.publish(event);

        ArgumentCaptor<TokenUsageOutboxDO> captor = ArgumentCaptor.forClass(TokenUsageOutboxDO.class);
        verify(mapper).insert(captor.capture());
        TokenUsageOutboxDO row = captor.getValue();
        assertEquals(event.getEventId(), row.getEventId());
        assertEquals(7L, row.getTenantId());
        assertEquals(UsageOutboxStatus.PENDING.name(), row.getStatus());
        assertTrue(row.getPayloadJson().contains(event.getEventId()));
        verify(metrics).staged();
    }
}
