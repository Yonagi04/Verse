package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.LlmCostAlertEvent;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** 与费用累计同事务落事实和收件快照，外部服务故障不进入此事务。 */
@Service @RequiredArgsConstructor
public class LlmCostAlertCaptureService {
    private final LlmCostAlertEventMapper events;
    private final LlmCostAlertDeliveryMapper deliveries;
    private final LlmServiceMapper services;
    private final ReliableDomainEventPublisher publisher;
    private final BudgetPeriodResolver time;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void capture(LlmCostBudgetDO config, List<CostBudgetPeriodDO> current) {
        var now = time.local(time.now());
        var retryAt = current.stream().filter(row -> LlmCostAlertPolicy.event(true, LlmCostBudgetLedger.limit(config, row.getPeriodType()),
                row.getUsedCostFen(), false, 80) != null).map(CostBudgetPeriodDO::getPeriodEnd).max(Comparator.naturalOrder()).orElse(null);
        for (var period : current) {
            String type = LlmCostAlertPolicy.event(Boolean.TRUE.equals(config.getCostLimitEnabled()), LlmCostBudgetLedger.limit(config, period.getPeriodType()),
                    period.getUsedCostFen(), Boolean.TRUE.equals(config.getWarningEnabled()), config.getWarningThresholdPercent());
            if (type == null || !period.getPeriodEnd().isAfter(now)) continue;
            // 调用方已持有配置行锁；相同服务的周期创建串行，DB 唯一键仍是最终保障。
            var existing = events.selectOne(Wrappers.lambdaQuery(LlmCostAlertEventDO.class).eq(LlmCostAlertEventDO::getTenantId, config.getTenantId())
                    .eq(LlmCostAlertEventDO::getServiceId, config.getServiceId()).eq(LlmCostAlertEventDO::getPeriodType, period.getPeriodType())
                    .eq(LlmCostAlertEventDO::getPeriodStart, period.getPeriodStart()).eq(LlmCostAlertEventDO::getEventType, type).last("FOR UPDATE"));
            if (existing != null) continue;
            var tenant = events.tenantSnapshot(config.getTenantId());
            var service = services.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class).eq(LlmServiceDO::getTenantId, config.getTenantId())
                    .eq(LlmServiceDO::getServiceId, config.getServiceId()));
            var event = new LlmCostAlertEventDO(); event.setId(SnowflakeIdUtil.nextId()); event.setEventId(String.valueOf(event.getId()));
            event.setTenantId(config.getTenantId()); event.setServiceId(config.getServiceId()); event.setPeriodType(period.getPeriodType());
            event.setPeriodStart(period.getPeriodStart()); event.setPeriodEnd(period.getPeriodEnd()); event.setEventType(type); event.setTriggeredAt(now);
            event.setLimitFen(LlmCostBudgetLedger.limit(config, period.getPeriodType())); event.setUsedCostFen(period.getUsedCostFen());
            event.setRemainingCostFen(event.getLimitFen().subtract(event.getUsedCostFen()).max(BigDecimal.ZERO));
            event.setThresholdPercent(config.getWarningThresholdPercent()); event.setConfigVersion(config.getConfigVersion());
            var channels = "LIMIT".equals(type) ? List.of("IN_APP", "SMS", "EMAIL") : LlmCostBudgetLedger.channels(config);
            event.setChannelsJson(JSON.toJSONString(channels)); event.setTenantName(tenant.getName()); event.setServiceName(service.getName());
            event.setRetryAt(retryAt); event.setActivityEnabledSnapshot(Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled()));
            event.setActivityEventId(String.valueOf(SnowflakeIdUtil.nextId())); event.setNotificationId(SnowflakeIdUtil.nextId());
            event.setStatus("CREATED"); event.setCreateTime(now); event.setUpdateTime(now); events.insert(event);
            if ("LIMIT".equals(type)) events.cancelWarning(config.getTenantId(), config.getServiceId(), period.getPeriodType(), period.getPeriodStart(), now);
            for (String channel : channels) {
                int recipients = deliveries.snapshotRecipients(event.getEventId(), config.getTenantId(), channel, now);
                if (recipients == 0) {
                    var skipped = new LlmCostAlertDeliveryDO(); skipped.setEventId(event.getEventId()); skipped.setChannel(channel);
                    skipped.setRecipientUserId(0L); skipped.setRequestId("empty:" + event.getEventId() + ":" + channel);
                    skipped.setStatus("SKIPPED"); skipped.setReasonCode("SKIPPED_NO_RECIPIENT"); skipped.setAttemptCount(0);
                    skipped.setCreateTime(now); skipped.setUpdateTime(now); skipped.setNextRetryAt(now); deliveries.insert(skipped);
                }
                if (!"IN_APP".equals(channel)) deliveries.deduplicateContacts(event.getEventId(), channel);
            }
            if ("LIMIT".equals(type)) {
                var task = new LlmCostAlertDeliveryDO(); task.setEventId(event.getEventId()); task.setChannel("ACTIVITY"); task.setRecipientUserId(0L);
                task.setRequestId("activity:" + event.getActivityEventId()); task.setStatus(event.getActivityEnabledSnapshot() ? "PENDING" : "SKIPPED");
                task.setReasonCode(event.getActivityEnabledSnapshot() ? null : "SKIPPED_RECORDING_DISABLED"); task.setAttemptCount(0);
                task.setNextRetryAt(now); task.setCreateTime(now); task.setUpdateTime(now); deliveries.insert(task);
            }
            var wakeup = new LlmCostAlertEvent(); wakeup.setTenantId(config.getTenantId()); wakeup.setAlertEventId(event.getEventId());
            wakeup.setKey(event.getEventId()); publisher.publish(wakeup, config.getTenantId());
        }
    }
}
