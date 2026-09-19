package com.yonagi.verse.async.event;

import com.yonagi.verse.common.enums.TenantActivityTargetType;
import com.yonagi.verse.common.enums.TenantActivityType;
import java.util.LinkedHashMap;
import java.util.Map;

/** 类型安全的动态草稿；只接受事件类型声明过的详情键。 */
public final class TenantActivityDraft {
    private final TenantActivityType type;
    private Long actorUserId;
    private TenantActivityTargetType targetType;
    private String targetId;
    private String targetName;
    private final Map<String, Object> details = new LinkedHashMap<>();

    private TenantActivityDraft(TenantActivityType type) {
        if (type == null) throw new IllegalArgumentException("动态类型不能为空");
        this.type = type;
    }

    public static TenantActivityDraft of(TenantActivityType type) { return new TenantActivityDraft(type); }
    public TenantActivityDraft actor(Long userId) { this.actorUserId = userId; return this; }
    public TenantActivityDraft target(TenantActivityTargetType type, Object id, String name) {
        this.targetType = type; this.targetId = id == null ? null : String.valueOf(id); this.targetName = name; return this;
    }
    public TenantActivityDraft detail(String key, Object value) {
        if (!type.allowedDetailKeys().contains(key)) throw new IllegalArgumentException("动态详情字段不在白名单: " + key);
        if (value != null) details.put(key, value);
        return this;
    }
    public TenantActivityType type() { return type; }
    public Long actorUserId() { return actorUserId; }
    public TenantActivityTargetType targetType() { return targetType; }
    public String targetId() { return targetId; }
    public String targetName() { return targetName; }
    public Map<String, Object> details() { return Map.copyOf(details); }
}
