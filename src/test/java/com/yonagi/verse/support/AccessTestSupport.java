package com.yonagi.verse.support;

import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 旧 Service 用例中的成员事实适配到真实领域校验，不模拟授权结论。 */
public final class AccessTestSupport {
    private AccessTestSupport() { }
    public static TenantAccessPolicy tenant(TenantMapper tenants, UserTenantService users) {
        UserTenantMapper mapper = mock(UserTenantMapper.class);
        when(mapper.selectOne(any())).thenAnswer(call -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserTenantDO>) call.getArgument(0);
            query.getSqlSegment();
            var values = query.getParamNameValuePairs();
            Long user = (Long) values.get("MPGENVAL1");
            Long tenant = (Long) values.get("MPGENVAL2");
            UserTenantDO existing = users.getOne(query);
            if (existing != null) return existing;
            if (!Boolean.TRUE.equals(users.isUserJoinedTenant(user, tenant))) return null;
            UserTenantDO relation = new UserTenantDO();
            relation.setUserId(user); relation.setTenantId(tenant);
            String role = users.getRoleByUserIdAndTenantId(user, tenant);
            relation.setRole(role == null ? "MEMBER" : role);
            return relation;
        });
        return new TenantAccessPolicy(tenants, mapper);
    }
}
