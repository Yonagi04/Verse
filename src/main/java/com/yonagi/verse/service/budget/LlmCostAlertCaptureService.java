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
        var service = services.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class).eq(LlmServiceDO::getTenantId, config.getTenantId())
                .eq(LlmServiceDO::getServiceId, config.getServiceId()));
        capture(config.getTenantId(), "LLM", config.getServiceId(), service.getName(),
                Boolean.TRUE.equals(config.getCostLimitEnabled()), Boolean.TRUE.equals(config.getWarningEnabled()),
                config.getWarningThresholdPercent(), config.getConfigVersion(), LlmCostBudgetLedger.channels(config),
                p -> LlmCostBudgetLedger.limit(config, p), current);
    }
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void capture(TenantCostBudgetDO config, List<CostBudgetPeriodDO> current) {
        var tenant = events.tenantSnapshot(config.getTenantId());
        capture(config.getTenantId(), "TENANT", config.getTenantId(), tenant.getName(),
                Boolean.TRUE.equals(config.getCostLimitEnabled()), Boolean.TRUE.equals(config.getWarningEnabled()),
                config.getWarningThresholdPercent(), config.getConfigVersion(), TenantCostBudgetLedger.channels(config),
                p -> TenantCostBudgetLedger.limit(config, p), current);
    }
    private void capture(Long tenantId, String scope, Long scopeId, String scopeName, boolean enabled, boolean warning,
            int threshold, long version, List<String> warningChannels, java.util.function.Function<String,BigDecimal> limit,
            List<CostBudgetPeriodDO> current) {
        var now = time.local(time.now());
        var retryAt = current.stream().filter(row -> LlmCostAlertPolicy.event(true, limit.apply(row.getPeriodType()),
                row.getUsedCostFen(), false, 80) != null).map(CostBudgetPeriodDO::getPeriodEnd).max(Comparator.naturalOrder()).orElse(null);
        for (var period : current) {
            String type = LlmCostAlertPolicy.event(enabled, limit.apply(period.getPeriodType()),
                    period.getUsedCostFen(), warning, threshold);
            if (type == null || !period.getPeriodEnd().isAfter(now)) continue;
            // 调用方已持有配置行锁；相同服务的周期创建串行，DB 唯一键仍是最终保障。
            var existing = events.selectOne(Wrappers.lambdaQuery(LlmCostAlertEventDO.class).eq(LlmCostAlertEventDO::getTenantId, tenantId)
                    .eq(LlmCostAlertEventDO::getScopeType, scope).eq(LlmCostAlertEventDO::getScopeId, scopeId).eq(LlmCostAlertEventDO::getPeriodType, period.getPeriodType())
                    .eq(LlmCostAlertEventDO::getPeriodStart, period.getPeriodStart()).eq(LlmCostAlertEventDO::getEventType, type).last("FOR UPDATE"));
            if (existing != null) continue;
            var tenant = events.tenantSnapshot(tenantId);
            var event = new LlmCostAlertEventDO(); event.setId(SnowflakeIdUtil.nextId()); event.setEventId(String.valueOf(event.getId()));
            event.setTenantId(tenantId); event.setScopeType(scope); event.setScopeId(scopeId); event.setScopeName(scopeName);
            event.setServiceId("LLM".equals(scope) ? scopeId : null); event.setPeriodType(period.getPeriodType());
            event.setPeriodStart(period.getPeriodStart()); event.setPeriodEnd(period.getPeriodEnd()); event.setEventType(type); event.setTriggeredAt(now);
            event.setLimitFen(limit.apply(period.getPeriodType())); event.setUsedCostFen(period.getUsedCostFen());
            event.setRemainingCostFen(event.getLimitFen().subtract(event.getUsedCostFen()).max(BigDecimal.ZERO));
            event.setThresholdPercent(threshold); event.setConfigVersion(version);
            var channels = "LIMIT".equals(type) ? List.of("IN_APP", "SMS", "EMAIL") : warningChannels;
            event.setChannelsJson(JSON.toJSONString(channels)); event.setTenantName(tenant.getName()); event.setServiceName("LLM".equals(scope) ? scopeName : null);
            event.setRetryAt(retryAt); event.setActivityEnabledSnapshot(Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled()));
            event.setActivityEventId(String.valueOf(SnowflakeIdUtil.nextId())); event.setNotificationId(SnowflakeIdUtil.nextId());
            event.setStatus("CREATED"); event.setCreateTime(now); event.setUpdateTime(now); events.insert(event);
            if ("LIMIT".equals(type)) events.cancelWarning(tenantId, scope, scopeId, period.getPeriodType(), period.getPeriodStart(), now);
            var recipientsSnapshot = deliveries.recipientSnapshot(tenantId);
            for (String channel : channels) {
                int recipients = 0;
                List<LlmCostAlertDeliveryDO> batch = new ArrayList<>(500);
                for (var recipient : recipientsSnapshot) {
                    if (!"IN_APP".equals(channel) && !List.of("ADMIN", "SUPER_ADMIN").contains(recipient.getRole())) continue;
                    var task = new LlmCostAlertDeliveryDO(); task.setEventId(event.getEventId()); task.setChannel(channel);
                    task.setRecipientUserId(recipient.getUserId());
                    task.setRequestId(cn.hutool.crypto.digest.DigestUtil.sha256Hex(event.getEventId()+":"+channel+":"+recipient.getUserId()));
                    String contact = "SMS".equals(channel) ? recipient.getPhone() : "EMAIL".equals(channel) ? recipient.getEmail() : null;
                    task.setContactEncrypted(contact); task.setContactHash("SMS".equals(channel) ? recipient.getPhoneHash() : "EMAIL".equals(channel) ? recipient.getEmailHash() : null);
                    boolean missing = !"IN_APP".equals(channel) && (contact == null || contact.isBlank());
                    task.setStatus(missing ? "SKIPPED" : "PENDING"); task.setReasonCode(missing ? "SKIPPED_NO_CONTACT" : null);
                    task.setAttemptCount(0); task.setNextRetryAt(now); task.setCreateTime(now); task.setUpdateTime(now);
                    batch.add(task); recipients++;
                    if (batch.size() == 500) { deliveries.insertBatch(batch); batch.clear(); }
                }
                if (!batch.isEmpty()) deliveries.insertBatch(batch);
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
            var wakeup = new LlmCostAlertEvent(); wakeup.setTenantId(tenantId); wakeup.setAlertEventId(event.getEventId());
            wakeup.setKey(event.getEventId()); publisher.publish(wakeup, tenantId);
        }
    }
}
