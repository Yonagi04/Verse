package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.CounterEvent;
import com.yonagi.verse.dao.entity.CounterEventDO;
import com.yonagi.verse.dao.mapper.CounterEventMapper;
import com.yonagi.verse.dao.mapper.TenantInviteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邀请码计数事件消费者：event_id 去重 + usage_count 原子 +1。
 * 去重插入与原子递增在同一事务内，保证重试下不重复计数。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterEventHandler implements DomainEventHandler<CounterEvent> {

    private final CounterEventMapper counterEventMapper;
    private final TenantInviteMapper tenantInviteMapper;

    @Override
    public String eventType() {
        return EventTag.COUNTER;
    }

    @Override
    public Class<CounterEvent> eventClass() {
        return CounterEvent.class;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(CounterEvent event) {
        CounterEventDO dedup = CounterEventDO.builder()
                .eventId(event.getEventId())
                .inviteId(event.getInviteId())
                .build();
        try {
            counterEventMapper.insert(dedup);
        } catch (DuplicateKeyException e) {
            log.info("[counter] 重复计数事件，跳过: eventId={}", event.getEventId());
            return;
        }
        int updated = tenantInviteMapper.incrementUsageCount(event.getInviteId());
        if (updated < 1) {
            log.warn("[counter] usage_count 递增失败: inviteId={}", event.getInviteId());
        }
    }
}
