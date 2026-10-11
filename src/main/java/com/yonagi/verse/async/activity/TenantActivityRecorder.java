package com.yonagi.verse.async.activity;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.async.event.TenantActivityEvent;
import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** 在业务事务内根据租户开关构建并可靠暂存动态事件。 */
@Component
@RequiredArgsConstructor
public class TenantActivityRecorder {
    private final TenantMapper tenantMapper;
    private final ActorSnapshotResolver actorResolver;
    private final ReliableDomainEventPublisher publisher;
    private final DomainOutboxProperties properties;

    /** 普通事件仅在当前事务观察到开关开启时记录。 */
    public boolean record(Long tenantId, TenantActivityDraft draft) {
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .select(TenantDO::getTenantId, TenantDO::getActivityRecordingEnabled)
                .eq(TenantDO::getTenantId, tenantId).eq(TenantDO::getDelFlag, 0));
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled())) return false;
        publish(tenantId, draft);
        return true;
    }

    /** 开启首事件和关闭末事件绕过更新后的开关过滤。 */
    public void recordToggle(Long tenantId, TenantActivityDraft draft) { publish(tenantId, draft); }

    /** 已固化开关的系统事实，不读取当前用户或最新动态开关。 */
    public void recordSystemSnapshot(com.yonagi.verse.dao.entity.LlmCostAlertEventDO source) {
        if (!Boolean.TRUE.equals(source.getActivityEnabledSnapshot())) return;
        TenantActivityEvent event = new TenantActivityEvent(); event.setEventId(source.getActivityEventId());
        event.setOccurredAt(source.getTriggeredAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        event.setTenantId(source.getTenantId()); event.setKey(source.getActivityEventId());
        event.setCategory(com.yonagi.verse.common.enums.TenantActivityCategory.LLM_SERVICE);
        event.setActivityType(com.yonagi.verse.common.enums.TenantActivityType.LLM_COST_LIMIT_REACHED);
        event.setActorType("SYSTEM"); event.setActorUsername("system"); event.setActorNickname("系统");
        event.setTargetType(com.yonagi.verse.common.enums.TenantActivityTargetType.LLM_SERVICE);
        event.setTargetId(String.valueOf(source.getServiceId())); event.setTargetName(source.getServiceName());
        java.util.Map<String,Object> details = new java.util.LinkedHashMap<>();
        details.put("period", source.getPeriodType()); details.put("periodStart", source.getPeriodStart().atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());
        details.put("periodEnd", source.getPeriodEnd().atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString()); details.put("triggeredAt", source.getTriggeredAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());
        if (source.getRetryAt()!=null) details.put("retryAt", source.getRetryAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());
        event.setDetails(details); publisher.publish(event, source.getTenantId());
    }

    private void publish(Long tenantId, TenantActivityDraft draft) {
        ActorSnapshot actor = actorResolver.resolve(draft.actorUserId());
        TenantActivityEvent event = new TenantActivityEvent();
        event.setTenantId(tenantId);
        event.setKey(String.valueOf(tenantId));
        event.setCategory(draft.type().category());
        event.setActivityType(draft.type());
        event.setActorUserId(actor.userId());
        event.setActorUsername(actor.username());
        event.setActorNickname(actor.nickname());
        event.setTargetType(draft.targetType());
        event.setTargetId(draft.targetId());
        event.setTargetName(draft.targetName());
        event.setDetails(draft.details());
        int bytes = JSON.toJSONString(event.getDetails()).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > Math.max(256, properties.getDetailsMaxBytes())) {
            throw new IllegalArgumentException("动态详情超过安全大小上限");
        }
        publisher.publish(event, tenantId);
    }
}
