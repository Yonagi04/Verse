package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.common.enums.DomainEventOutboxStatus;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DomainOutboxRelayTest {
    private DomainOutboxClaimService claim; private DomainEventOutboxMapper mapper; private RocketMQTemplate template;
    private DomainOutboxProperties properties; private DomainOutboxRelay relay; private DomainEventOutboxDO row;
    @BeforeEach void setUp(){
        claim=mock(DomainOutboxClaimService.class); mapper=mock(DomainEventOutboxMapper.class); template=mock(RocketMQTemplate.class);
        properties=new DomainOutboxProperties(); relay=new DomainOutboxRelay(claim,mapper,template,properties,mock(DomainOutboxMetrics.class));
        ReflectionTestUtils.setField(relay,"topic","verse-event"); ReflectionTestUtils.setField(relay,"timeout",3000L);
        row=new DomainEventOutboxDO(); row.setId(1L); row.setEventId("event-1"); row.setTenantId(20L);
        row.setEventType("TENANT_ACTIVITY"); row.setMessageKey("20"); row.setPayloadJson("{}"); row.setAttemptCount(0);
        when(claim.claim(anyString())).thenReturn(List.of(row));
    }
    @Test void pausedRelayDoesNotClaim(){properties.setRelayEnabled(false);relay.relay();verifyNoInteractions(claim,mapper,template);}
    @Test void sendOkMarksPublished(){
        SendResult result=mock(SendResult.class);when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(template.syncSendOrderly(anyString(),any(Message.class),eq("20"),anyLong())).thenReturn(result);when(mapper.markPublished(anyLong(),anyString(),any())).thenReturn(1);
        relay.relay();verify(mapper).markPublished(eq(1L),anyString(),any());
    }
    @Test void successfulSendWithStateUpdateFailureSchedulesSameEventForRetry(){
        SendResult result=mock(SendResult.class);when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(template.syncSendOrderly(anyString(),any(Message.class),eq("20"),anyLong())).thenReturn(result);
        when(mapper.markPublished(anyLong(),anyString(),any())).thenReturn(0);
        relay.relay();
        verify(mapper).markPublishFailure(eq(1L),anyString(),eq(DomainEventOutboxStatus.RETRY.name()),any(),
                contains("状态更新失败"),any());
    }
    @Test void brokerFailureBacksOffThenFailsAtLimit(){
        when(template.syncSendOrderly(anyString(),any(Message.class),anyString(),anyLong())).thenThrow(new IllegalStateException("broker down"));
        relay.relay();verify(mapper).markPublishFailure(eq(1L),anyString(),eq(DomainEventOutboxStatus.RETRY.name()),any(),contains("broker down"),any());
        reset(mapper);properties.setMaxAttempts(1);relay.relay();verify(mapper).markPublishFailure(eq(1L),anyString(),eq(DomainEventOutboxStatus.FAILED.name()),any(),contains("broker down"),any());
    }
}
