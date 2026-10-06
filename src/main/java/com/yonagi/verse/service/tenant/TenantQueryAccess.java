package com.yonagi.verse.service.tenant;

import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 租户查询的缓存适配，业务判断全部交给领域校验。 */
@Component
@RequiredArgsConstructor
public class TenantQueryAccess implements QueryAccessPolicy {
    private final TenantAccessPolicy access;
    @Override public void check(Object[] args) {
        if (args[0] instanceof UserContext actor) access.requireContext(actor, (Long) args[1]);
        else access.requireMember((Long) args[0], (Long) args[1]);
    }
}
