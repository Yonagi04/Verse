package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.UsageOutboxStatus;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.messaging.Message;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageOutboxRelayTest {
    private UsageOutboxClaimService claimService;
    private TokenUsageOutboxMapper mapper;
    private RocketMQTemplate template;
    private UsageOutboxProperties properties;
    private UsageOutboxMetrics metrics;
    private UsageOutboxRelay relay;
    private TokenUsageOutboxDO row;

    @BeforeEach
    void setUp() {
        claimService = mock(UsageOutboxClaimService.class);
        mapper = mock(TokenUsageOutboxMapper.class);
        template = mock(RocketMQTemplate.class);
        metrics = mock(UsageOutboxMetrics.class);
        properties = new UsageOutboxProperties();
        relay = new UsageOutboxRelay(claimService, mapper, template, properties, metrics);
        ReflectionTestUtils.setField(relay, "topic", "verse-event");
        ReflectionTestUtils.setField(relay, "sendTimeout", 3000L);
        row = new TokenUsageOutboxDO();
        row.setId(1L);
        row.setEventId("event-1");
        row.setEventType("TokenUsage");
        row.setMessageKey("event-1");
        row.setPayloadJson("{}");
        row.setAttemptCount(0);
        when(claimService.claim(anyString())).thenReturn(List.of(row));
    }

    @Test
    void marksPublishedOnlyAfterSendOk() {
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(result.getMsgId()).thenReturn("msg-1");
        when(template.syncSendOrderly(anyString(), any(Message.class), anyString(), anyLong())).thenReturn(result);
        when(mapper.markPublished(anyLong(), anyString(), any())).thenReturn(1);

        relay.relay();

        verify(mapper).markPublished(eq(1L), anyString(), any());
        verify(mapper, never()).markPublishFailure(anyLong(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void ambiguousAcknowledgementSchedulesRetryWithSameEvent() {
        when(template.syncSendOrderly(anyString(), any(Message.class), anyString(), anyLong())).thenReturn(null);

        relay.relay();

        verify(mapper).markPublishFailure(eq(1L), anyString(), eq(UsageOutboxStatus.RETRY.name()),
                any(), anyString(), any());
        verify(mapper, never()).markPublished(anyLong(), anyString(), any());
    }

    @Test
    void retryExhaustionMovesToReplayableFailure() {
        properties.setMaxAttempts(1);
        when(template.syncSendOrderly(anyString(), any(Message.class), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("broker down"));

        relay.relay();

        verify(mapper).markPublishFailure(eq(1L), anyString(), eq(UsageOutboxStatus.FAILED.name()),
                any(), contains("broker down"), any());
    }
}
