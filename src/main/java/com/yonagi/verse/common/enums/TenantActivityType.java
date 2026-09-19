package com.yonagi.verse.common.enums;

import java.util.Set;

/** 首批租户动态事件目录及其详情白名单。 */
public enum TenantActivityType {
    ACTIVITY_RECORDING_ENABLED(TenantActivityCategory.TENANT, Set.of("changedFields")),
    ACTIVITY_RECORDING_DISABLED(TenantActivityCategory.TENANT, Set.of("changedFields")),
    TENANT_SETTINGS_UPDATED(TenantActivityCategory.TENANT, Set.of("changedFields")),
    TENANT_PROFILE_UPDATED(TenantActivityCategory.TENANT, Set.of("changedFields")),
    TENANT_DISABLED(TenantActivityCategory.TENANT, Set.of()),
    MEMBER_JOINED(TenantActivityCategory.MEMBER, Set.of("joinSource")),
    MEMBER_LEFT(TenantActivityCategory.MEMBER, Set.of()),
    MEMBER_REMOVED(TenantActivityCategory.MEMBER, Set.of()),
    MEMBER_ROLE_CHANGED(TenantActivityCategory.MEMBER, Set.of("oldRole", "newRole")),
    JOIN_REQUEST_REJECTED(TenantActivityCategory.MEMBER, Set.of()),
    INVITE_ENABLED(TenantActivityCategory.INVITE, Set.of("status", "expiresAt")),
    INVITE_DISABLED(TenantActivityCategory.INVITE, Set.of("status", "expiresAt")),
    LLM_SERVICE_CREATED(TenantActivityCategory.LLM_SERVICE, Set.of("provider", "modelName")),
    LLM_SERVICE_UPDATED(TenantActivityCategory.LLM_SERVICE, Set.of("changedFields", "credentialChanged")),
    LLM_SERVICE_ENABLED(TenantActivityCategory.LLM_SERVICE, Set.of()),
    LLM_SERVICE_DISABLED(TenantActivityCategory.LLM_SERVICE, Set.of()),
    LLM_SERVICE_REMOVED(TenantActivityCategory.LLM_SERVICE, Set.of());

    private final TenantActivityCategory category;
    private final Set<String> allowedDetailKeys;

    TenantActivityType(TenantActivityCategory category, Set<String> allowedDetailKeys) {
        this.category = category;
        this.allowedDetailKeys = allowedDetailKeys;
    }

    public TenantActivityCategory category() { return category; }
    public Set<String> allowedDetailKeys() { return allowedDetailKeys; }
}
