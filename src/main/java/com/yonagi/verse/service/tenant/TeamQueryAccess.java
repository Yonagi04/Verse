package com.yonagi.verse.service.tenant;

import com.yonagi.verse.common.cache.QueryAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 团队列表查询的缓存适配。 */
@Component
@RequiredArgsConstructor
public class TeamQueryAccess implements QueryAccessPolicy {
    private final TenantAccessPolicy access;
    @Override public void check(Object[] args) { access.requireTeamMember((Long) args[0], (Long) args[1]); }
}
