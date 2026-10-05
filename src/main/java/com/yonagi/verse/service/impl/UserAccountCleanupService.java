package com.yonagi.verse.service.impl;

import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.UserClosedEvent;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.dao.mapper.UsageCleanupMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 每次消费只完成一批统计删除；续跑任务与删除结果一起提交。 */
@Service
@RequiredArgsConstructor
public class UserAccountCleanupService {
    private static final int BATCH_SIZE = 500;
    private final UserMapper users;
    private final UsageCleanupMapper usage;
    private final TokenUsageHourlyAggMapper projection;
    private final ReliableDomainEventPublisher events;
    private final DomainEventOutboxMapper outbox;

    public static boolean isClosed(UserDO user) {
        return user != null && Integer.valueOf(2).equals(user.getStatus()) && Integer.valueOf(1).equals(user.getDelFlag());
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void cleanUsageBatch(UserClosedEvent event) {
        // 与投影重建固定按投影锁在先；随后用户锁与用量生产、消费串行化。
        if (projection.lockProjection() == null) throw new IllegalStateException("缺少统计投影锁初始化行");
        if (!isClosed(users.lockResourceOwner(event.getUserId()))) {
            throw new IllegalStateException("只能清理已注销账号的资源");
        }
        UserDO user = users.selectCleanupState(event.getUserId());
        LocalDateTime now = LocalDateTime.now();
        if (user.getResourceCleanupAt() == null) {
            var ids = usage.selectUsageIds(event.getUserId(), BATCH_SIZE);
            if (!ids.isEmpty()) {
                usage.deleteCosts(ids);
                usage.deleteUsage(event.getUserId(), ids);
            }
            int hourly = usage.deleteHourlyBatch(event.getUserId(), BATCH_SIZE);
            int pending = usage.deleteOutboxBatch(event.getUserId(), BATCH_SIZE);
            if (ids.size() == BATCH_SIZE || hourly == BATCH_SIZE || pending == BATCH_SIZE) {
                events.publish(UserClosedEvent.next(event.getUserId()), 0L);
            } else if (users.completeResourceCleanup(event.getUserId(), now) != 1) {
                throw new IllegalStateException("注销清理完成状态更新失败");
            }
        }
        outbox.reconcileUserCleanup(event.getEventId(), now);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void enqueueHistorical(Long userId) {
        if (!isClosed(users.lockResourceOwner(userId))) return;
        UserDO user = users.selectCleanupState(userId);
        if (user.getResourceCleanupAt() == null && outbox.countEvent(UserClosedEvent.initialId(userId)) == 0) {
            events.publish(UserClosedEvent.initial(userId), 0L);
        }
    }
}
