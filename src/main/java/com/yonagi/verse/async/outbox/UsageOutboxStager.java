package com.yonagi.verse.async.outbox;

import com.yonagi.verse.dao.mapper.UserMapper;
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
    private final UserMapper users;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void stage(TokenUsageEvent event, String payload, LocalDateTime now) {
        if (!properties.isEnabled()) throw new IllegalStateException("usage outbox disabled");
        if (event == null || event.getUserId() == null) throw new IllegalArgumentException("用量事件缺少用户");
        var owner = users.lockResourceOwner(event.getUserId());
        if (owner == null) throw new IllegalStateException("用量用户不存在");
        // 在途预算仍完成结算，已注销用户的统计载荷不再进入 Outbox。
        if (Integer.valueOf(2).equals(owner.getStatus()) || !Integer.valueOf(0).equals(owner.getDelFlag())) return;
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
        row.setUserId(event.getUserId());
        row.setEventType(event.eventType()); row.setMessageKey(event.getKey()); row.setPayloadJson(payload);
        row.setStatus("PENDING"); row.setAttemptCount(0); row.setNextRetryAt(now);
        row.setCreateTime(now); row.setUpdateTime(now);
        mapper.insert(row);
    }
}
