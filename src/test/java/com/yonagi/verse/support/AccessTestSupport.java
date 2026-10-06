package com.yonagi.verse.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 旧 Service 用例中的成员事实适配到真实领域校验，不模拟授权结论。 */
public final class AccessTestSupport {
    private AccessTestSupport() { }
    public static TenantAccessPolicy tenant(TenantMapper tenants, UserTenantService users) {
        // 夹具解析成员查询前自行准备元数据，避免依赖其他测试或 Spring 的初始化顺序。
        if (TableInfoHelper.getTableInfo(UserTenantDO.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "access-test-support"), UserTenantDO.class);
        }
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
