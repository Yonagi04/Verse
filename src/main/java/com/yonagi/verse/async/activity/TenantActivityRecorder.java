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
