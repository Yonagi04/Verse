package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.entity.TokenUsageCostDO;
import com.yonagi.verse.dao.mapper.TokenUsageCostMapper;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenUsageEventHandlerTest {
    @Test
    void springSelectsProductionConstructorWhenMultipleConstructorsExist() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TokenUsageMapper.class, () -> mock(TokenUsageMapper.class));
            context.registerBean(TokenUsageCostMapper.class, () -> mock(TokenUsageCostMapper.class));
            context.register(TokenUsageEventHandler.class);

            context.refresh();

            assertNotNull(context.getBean(TokenUsageEventHandler.class));
        }
    }

    @Test
    void preservesNormalizedSnapshotInFact() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mapper, null);
        TokenUsageEvent event = validEvent();

        handler.onEvent(event);

        ArgumentCaptor<TokenUsageDO> captor = ArgumentCaptor.forClass(TokenUsageDO.class);
        verify(mapper).insert(captor.capture());
        TokenUsageDO fact = captor.getValue();
        assertEquals(event.getEventId(), fact.getEventId());
        assertEquals(12L, fact.getNormalizedTotalTokens());
        assertEquals("openai-compatible", fact.getUsageParser());
        assertEquals(new BigDecimal("0.1"), fact.getEstimatedCostFen());
        assertEquals(LocalDateTime.of(2026,9,6,9,2,3),fact.getRequestStartedAt());
    }

    @Test
    void preservesTypedNonTokenMeasurementsWithoutInventingTokens() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        TokenUsageEvent event = validEvent();
        event.setOperation("IMAGE_GENERATION");
        event.setImageCount(2);
        event.setPromptTokens(null);
        event.setCompletionTokens(null);
        event.setTotalTokens(null);
        event.setNormalizedUsage(null);
        event.setUsageSource("UNKNOWN");
        event.setCostResult(CostResult.of(CostStatus.UNPRICED));
        new TokenUsageEventHandler(mapper, null).onEvent(event);
        ArgumentCaptor<TokenUsageDO> captor = ArgumentCaptor.forClass(TokenUsageDO.class);
        verify(mapper).insert(captor.capture());
        assertEquals("IMAGE_GENERATION", captor.getValue().getOperation());
        assertEquals(2, captor.getValue().getImageCount());
        assertNull(captor.getValue().getTotalTokens());
    }

    @Test
    void persistsUsageAndCostAsACompletePair() {
        TokenUsageMapper usageMapper=mock(TokenUsageMapper.class);
        TokenUsageCostMapper costMapper=mock(TokenUsageCostMapper.class);
        doAnswer(invocation->{invocation.<TokenUsageDO>getArgument(0).setId(99L);return 1;}).when(usageMapper).insert(any());
        new TokenUsageEventHandler(usageMapper,costMapper).onEvent(validEvent());
        ArgumentCaptor<TokenUsageCostDO> captor=ArgumentCaptor.forClass(TokenUsageCostDO.class);
        verify(costMapper).insert(captor.capture());
        assertEquals(99L,captor.getValue().getUsageId());
        assertEquals(CostStatus.CALCULATED.name(),captor.getValue().getCostStatus());
        assertEquals(0,captor.getValue().getEstimatedCostFen().compareTo(new BigDecimal("0.1")));
    }

    @Test
    void duplicateEventIsAcknowledgedButOtherUniqueConflictPropagates() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mapper, null);
        TokenUsageEvent event = validEvent();
        doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any());
        when(mapper.countByEventId(event.getEventId())).thenReturn(1L);
        assertDoesNotThrow(() -> handler.onEvent(event));

        when(mapper.countByEventId(event.getEventId())).thenReturn(0L);
        assertThrows(DuplicateKeyException.class, () -> handler.onEvent(event));
    }

    @Test
    void invalidPayloadPropagatesForRocketMqRetry() {
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mock(TokenUsageMapper.class), null);
        assertThrows(IllegalArgumentException.class, () -> handler.onEvent(new TokenUsageEvent()));
    }

    @Test
    void transientDatabaseFailurePropagatesForRocketMqRetry() {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        doThrow(new IllegalStateException("database unavailable")).when(mapper).insert(any());
        TokenUsageEventHandler handler = new TokenUsageEventHandler(mapper, null);
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
