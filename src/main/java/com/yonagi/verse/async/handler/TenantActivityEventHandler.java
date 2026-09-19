package com.yonagi.verse.async.handler;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.TenantActivityEvent;
import com.yonagi.verse.async.outbox.DomainOutboxMetrics;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import com.yonagi.verse.dao.mapper.TenantActivityLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import java.time.*;

/** 仅使用事件快照幂等写入租户动态事实，不查询任何实时业务状态。 */
@Slf4j @Component @RequiredArgsConstructor
public class TenantActivityEventHandler implements DomainEventHandler<TenantActivityEvent> {
    private final TenantActivityLogMapper mapper; private final DomainOutboxMetrics metrics;
    @Override public String eventType(){return EventTag.TENANT_ACTIVITY;}
    @Override public Class<TenantActivityEvent> eventClass(){return TenantActivityEvent.class;}
    @Override public void onEvent(TenantActivityEvent event){
        try{
            validate(event); TenantActivityLogDO row=new TenantActivityLogDO();
            row.setEventId(event.getEventId()); row.setTenantId(event.getTenantId()); row.setCategory(event.getCategory().name());
            row.setActivityType(event.getActivityType().name()); row.setActorUserId(event.getActorUserId());
            row.setActorUsername(event.getActorUsername()); row.setActorNickname(event.getActorNickname());
            row.setTargetType(event.getTargetType()==null?null:event.getTargetType().name()); row.setTargetId(event.getTargetId());
            row.setTargetName(event.getTargetName()); row.setDetailJson(JSON.toJSONString(event.getDetails()));
            row.setSchemaVersion(event.getSchemaVersion());
            LocalDateTime occurred=LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getOccurredAt()),ZoneId.of("Asia/Shanghai"));
            row.setOccurredAt(occurred); row.setCreateTime(LocalDateTime.now());
            try{mapper.insert(row);}catch(DuplicateKeyException e){
                if(mapper.countByEventId(event.getEventId())>0){metrics.duplicate(); return;} throw e;
            }
            metrics.recordEndToEnd(Duration.between(occurred,LocalDateTime.now()));
        }catch(RuntimeException e){metrics.consumeFailed();throw e;}
    }
    private void validate(TenantActivityEvent e){
        if(e==null||e.getEventId()==null||e.getEventId().isBlank()||e.getTenantId()==null||e.getActorUserId()==null
                ||e.getActorUsername()==null||e.getActorUsername().isBlank()||e.getCategory()==null||e.getActivityType()==null
                ||e.getOccurredAt()<=0) throw new IllegalArgumentException("租户动态事件缺少必要字段");
        if(e.getSchemaVersion()!=TenantActivityEvent.CURRENT_SCHEMA_VERSION) throw new IllegalArgumentException("不支持的租户动态事件版本");
        if(e.getCategory()!=e.getActivityType().category()) throw new IllegalArgumentException("租户动态分类与类型不一致");
    }
}
