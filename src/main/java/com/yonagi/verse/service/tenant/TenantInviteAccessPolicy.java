package com.yonagi.verse.service.tenant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dao.mapper.TenantInviteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Date;

/** 邀请码预览与加入操作共用有效性规则，时间边界实时判断。 */
@Component
@RequiredArgsConstructor
public class TenantInviteAccessPolicy implements QueryAccessPolicy {
    private final TenantInviteMapper invites;
    private final TenantAccessPolicy tenants;
    public TenantInviteDO requireValidCode(String code) {
        TenantInviteDO invite = invites.selectOne(Wrappers.lambdaQuery(TenantInviteDO.class).eq(TenantInviteDO::getCode, code));
        if (invite == null || !Integer.valueOf(1).equals(invite.getIsActive())
                || invite.getExpiresAt() != null && !invite.getExpiresAt().after(new Date()))
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        return invite;
    }
    @Override public void check(Object[] args) {
        TenantInviteDO invite = requireValidCode((String) args[0]);
        tenants.activeTenant(invite.getTenantId());
    }
}
