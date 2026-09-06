package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenUsageEventHandlerTest {
    @Test
    void preservesNormalizedSnapshotInFact() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mapper);
        TokenUsageEvent event = validEvent();

        handler.onEvent(event);

        ArgumentCaptor<TokenUsageDO> captor = ArgumentCaptor.forClass(TokenUsageDO.class);
        verify(mapper).insert(captor.capture());
        TokenUsageDO fact = captor.getValue();
        assertEquals(event.getEventId(), fact.getEventId());
        assertEquals(12L, fact.getNormalizedTotalTokens());
        assertEquals("openai-compatible", fact.getUsageParser());
        assertEquals(new BigDecimal("0.1"), fact.getEstimatedCostFen());
    }

    @Test
    void duplicateEventIsAcknowledgedButOtherUniqueConflictPropagates() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mapper);
        TokenUsageEvent event = validEvent();
        doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any());
        when(mapper.countByEventId(event.getEventId())).thenReturn(1L);
        assertDoesNotThrow(() -> handler.onEvent(event));

        when(mapper.countByEventId(event.getEventId())).thenReturn(0L);
        assertThrows(DuplicateKeyException.class, () -> handler.onEvent(event));
    }

    @Test
    void invalidPayloadPropagatesForRocketMqRetry() {
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mock(TokenUsageMapper.class));
        assertThrows(IllegalArgumentException.class, () -> handler.onEvent(new TokenUsageEvent()));
    }

    @Test
    void transientDatabaseFailurePropagatesForRocketMqRetry() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        doThrow(new IllegalStateException("database unavailable")).when(mapper).insert(any());
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mapper);
        assertThrows(IllegalStateException.class, () -> handler.onEvent(validEvent()));
    }

    private TokenUsageEvent validEvent() {
        TokenUsageEvent event = new TokenUsageEvent();
        event.setUserId(1L);
        event.setTenantId(2L);
        event.setApiKeyId(3L);
        event.setServiceId(4L);
        event.setModel("model");
        event.setStatus("SUCCESS");
        event.setUsageSource("EXACT");
        event.setRequestStartedAt(Instant.parse("2026-09-06T01:02:03Z"));
        event.setNormalizedUsage(new UsageBreakdown(10L, 2L, 0L, 2L, 12L,
                "EXACT", "openai-compatible", null, true));
        event.setCostResult(new CostResult(CostStatus.CALCULATED, new BigDecimal("0.1")));
        return event;
    }
}
