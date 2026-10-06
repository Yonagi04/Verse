package com.yonagi.verse.service.tenant;

import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** 批量概览按实时目标成员与角色隔离，保留跨当前租户读取。 */
@Component
@RequiredArgsConstructor
public class OverviewQueryAccess implements QueryAccessPolicy {
    private final UserTenantMapper memberships;
    @Override public void check(Object[] args) { }
    @Override public void contributeParameters(List<Object> parameters, Object[] args) {
        parameters.add(memberships.selectOverviewMemberships((Long) args[0]).stream()
                .map(relation -> Map.of("tenant", relation.getTenantId(), "role", relation.getRole())).toList());
    }
}
