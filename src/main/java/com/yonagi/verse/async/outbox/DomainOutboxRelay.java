package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.common.enums.DomainEventOutboxStatus;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.UUID;

/** 将可靠事件按业务 key 有序投递到统一 Topic。 */
@Slf4j @Component @RequiredArgsConstructor
public class DomainOutboxRelay {
    private final DomainOutboxClaimService claimService; private final DomainEventOutboxMapper mapper;
    private final RocketMQTemplate rocketMQTemplate; private final DomainOutboxProperties properties; private final DomainOutboxMetrics metrics;
    private final String owner=UUID.randomUUID().toString();
    @Value("${rocketmq.producer.topic}") private String topic;
    @Value("${rocketmq.producer.send-message-timeout:3000}") private long timeout;

    @Scheduled(fixedDelayString="${verse.async.domain-outbox.relay-delay-ms:1000}")
    public void relay(){
        if(!properties.isRelayEnabled()) return;
        try { claimService.claim(owner).forEach(this::sendOne); metrics.refresh(); }
        catch(RuntimeException e){ log.error("[domain-outbox] Relay 批次失败: owner={}",owner,e); }
    }
    private void sendOne(DomainEventOutboxDO row){
        Instant started=Instant.now();
        try{
            Message<String> message=MessageBuilder.withPayload(row.getPayloadJson()).setHeader(MessageConst.PROPERTY_KEYS,row.getEventId()).build();
            SendResult result=rocketMQTemplate.syncSendOrderly(topic+":"+row.getEventType(),message,row.getMessageKey(),timeout);
            if(result==null||result.getSendStatus()!=SendStatus.SEND_OK) throw new IllegalStateException("RocketMQ 未返回 SEND_OK");
            if(mapper.markPublished(row.getId(),owner,LocalDateTime.now())!=1) throw new IllegalStateException("发送成功但状态更新失败，等待租约恢复重发");
            Instant staged=row.getCreateTime()==null?started:row.getCreateTime().atZone(ZoneId.systemDefault()).toInstant();
            metrics.recordPublishLatency(Duration.between(staged,Instant.now()));
            log.info("[domain-outbox] 发布成功: eventId={}, tenantId={}, eventType={}",row.getEventId(),row.getTenantId(),row.getEventType());
        }catch(RuntimeException e){
            int attempt=row.getAttemptCount()+1; boolean exhausted=attempt>=Math.max(1,properties.getMaxAttempts());
            String status=exhausted?DomainEventOutboxStatus.FAILED.name():DomainEventOutboxStatus.RETRY.name(); LocalDateTime now=LocalDateTime.now();
            long initial=Math.max(1,properties.getInitialBackoffMs()),max=Math.max(initial,properties.getMaxBackoffMs());
            int shift=Math.min(30,Math.max(0,attempt-1)); long backoff;
            try{backoff=Math.min(max,Math.multiplyExact(initial,1L<<shift));}catch(ArithmeticException ignored){backoff=max;}
            String summary=(e.getClass().getSimpleName()+": "+e.getMessage()); if(summary.length()>2000) summary=summary.substring(0,2000);
            mapper.markPublishFailure(row.getId(),owner,status,exhausted?now:now.plusNanos(backoff*1_000_000),summary,now);
            metrics.publishFailed();
            log.error("[domain-outbox] 发布失败: eventId={}, tenantId={}, attempt={}, status={}",row.getEventId(),row.getTenantId(),attempt,status,e);
        }
    }
}
