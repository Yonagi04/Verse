package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.common.enums.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 版本化租户动态事件，保存展示所需的不可变安全快照。 */
@Getter
@Setter
@NoArgsConstructor
public class TenantActivityEvent extends DomainEvent {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** 租户 ID。 */ private Long tenantId;
    /** 动态分类。 */ private TenantActivityCategory category;
    /** 动态类型。 */ private TenantActivityType activityType;
    /** 结构版本。 */ private int schemaVersion = CURRENT_SCHEMA_VERSION;
    /** 操作人用户 ID。 */ private Long actorUserId;
    /** 操作人用户名快照。 */ private String actorUsername;
    /** 操作人昵称快照。 */ private String actorNickname;
    /** 目标对象类型。 */ private TenantActivityTargetType targetType;
    /** 目标对象业务 ID。 */ private String targetId;
    /** 目标对象名称快照。 */ private String targetName;
    /** 白名单化详情。 */ private Map<String, Object> details = new LinkedHashMap<>();

    @Override public String eventType() { return EventTag.TENANT_ACTIVITY; }
}
