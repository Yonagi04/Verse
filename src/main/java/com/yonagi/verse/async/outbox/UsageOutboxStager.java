package com.yonagi.verse.async.outbox;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 强制加入结算事务，避免报表事件已提交而预算回滚。 */
@Component
@RequiredArgsConstructor
public class UsageOutboxStager {
    private final TokenUsageOutboxMapper mapper;
    private final UsageOutboxProperties properties;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void stage(TokenUsageEvent event, String payload, LocalDateTime now) {
        if (!properties.isEnabled()) throw new IllegalStateException("usage outbox disabled");
        TokenUsageOutboxDO existing = mapper.selectOne(Wrappers.lambdaQuery(TokenUsageOutboxDO.class)
                .eq(TokenUsageOutboxDO::getEventId, event.getEventId()).last("FOR UPDATE"));
        if (existing != null) {
            if (!JSON.parseObject(existing.getPayloadJson()).equals(JSON.parseObject(payload))) {
                throw new IllegalStateException("outbox snapshot conflict");
            }
            return;
        }
        TokenUsageOutboxDO row = new TokenUsageOutboxDO();
        row.setEventId(event.getEventId()); row.setTenantId(event.getTenantId());
        row.setEventType(event.eventType()); row.setMessageKey(event.getKey()); row.setPayloadJson(payload);
        row.setStatus("PENDING"); row.setAttemptCount(0); row.setNextRetryAt(now);
        row.setCreateTime(now); row.setUpdateTime(now);
        mapper.insert(row);
    }
}
