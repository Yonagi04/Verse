package com.yonagi.verse.service.tenant;

import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 动态读取实时检查开关，不缓存访问资格。 */
@Component
@RequiredArgsConstructor
public class TenantActivityAccessPolicy implements QueryAccessPolicy {
    private final TenantAccessPolicy tenants;
    public TenantDO requireReadable(Long userId, Long tenantId) { return tenants.requireMember(userId, tenantId); }
    public TenantDO requireRecording(Long userId, Long tenantId) {
        TenantDO tenant = requireReadable(userId, tenantId);
        if (!Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_ACTIVITY_RECORDING_DISABLED);
        return tenant;
    }
    @Override public void check(Object[] args) { requireRecording((Long) args[0], (Long) args[1]); }
}
