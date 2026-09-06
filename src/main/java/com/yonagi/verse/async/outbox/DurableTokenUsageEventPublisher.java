package com.yonagi.verse.async.outbox;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.TokenUsageEventPublisher;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.UsageOutboxStatus;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 先写本地 Outbox、再由 Relay 异步发送 RocketMQ。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DurableTokenUsageEventPublisher implements TokenUsageEventPublisher {
    private final TokenUsageOutboxMapper outboxMapper;
    private final UsageOutboxProperties properties;
    private final UsageOutboxMetrics metrics;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void publish(TokenUsageEvent event) {
        if (!properties.isEnabled()) {
            log.warn("[usage-outbox] 功能开关已关闭，未暂存事件: eventId={}", event.getEventId());
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            TokenUsageOutboxDO row = new TokenUsageOutboxDO();
            row.setEventId(event.getEventId());
            row.setTenantId(event.getTenantId());
            row.setEventType(event.eventType());
            row.setMessageKey(event.getKey());
            row.setPayloadJson(JSON.toJSONString(event));
            row.setStatus(UsageOutboxStatus.PENDING.name());
            row.setAttemptCount(0);
            row.setNextRetryAt(now);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            outboxMapper.insert(row);
            metrics.staged();
        } catch (RuntimeException e) {
            metrics.stagingFailed();
            log.error("[usage-outbox] 计费用量事件暂存失败: eventId={}, tenantId={}",
                    event.getEventId(), event.getTenantId(), e);
            throw e;
        }
    }
}
