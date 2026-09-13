package com.yonagi.verse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.req.TenantSettingsUpdateReqDTO;
import com.yonagi.verse.dto.resp.TenantSettingsRespDTO;
import com.yonagi.verse.resilience.api.RateLimiter;
import com.yonagi.verse.service.TenantSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户自定义设置服务实现。
 *
 * @author Yonagi
 */
@Service
@RequiredArgsConstructor
public class TenantSettingsServiceImpl implements TenantSettingsService {

    private final TenantMapper tenantMapper;
    private final UserTenantMapper userTenantMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimiter rateLimiter;

    @Override
    public TenantSettingsRespDTO getSettings(Long userId, Long tenantId) {
        UserTenantDO membership = requireMembership(userId, tenantId);
        TenantDO tenant = requireActiveTenant(tenantId);
        return toResponse(tenant, membership.getRole());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantSettingsRespDTO updateSettings(Long userId, Long tenantId,
                                                 TenantSettingsUpdateReqDTO requestParam) {
        UserTenantDO membership = requireMembership(userId, tenantId);
        RoleEnum role = RoleEnum.valueOf(membership.getRole());
        if (!role.isAdmin()) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        }

        TenantDO tenant = requireActiveTenant(tenantId);
        Integer approvalMode = requestParam.getJoinApprovalMode();
        if (approvalMode == null || (approvalMode != 0 && approvalMode != 1)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_APPROVAL_MODE_INVALID);
        }
        if ("PERSONAL".equals(tenant.getType()) && approvalMode != 0) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_APPROVAL_MODE_INVALID);
        }

        String name = StrUtil.trim(requestParam.getName());
        String description = StrUtil.trimToNull(requestParam.getDescription());
        Integer rpm = normalizeLimit(requestParam.getRateLimitRpm());
        Integer tpm = normalizeLimit(requestParam.getRateLimitTpm());
        int updated = tenantMapper.update(Wrappers.lambdaUpdate(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0)
                .set(TenantDO::getName, name)
                .set(TenantDO::getDescription, description)
                .set(TenantDO::getJoinApprovalMode,
                        "PERSONAL".equals(tenant.getType()) ? 0 : approvalMode)
                .set(TenantDO::getAuditEnabled, Boolean.TRUE.equals(requestParam.getAuditEnabled()) ? 1 : 0)
                .set(TenantDO::getRateLimitRpm, rpm)
                .set(TenantDO::getRateLimitTpm, tpm));
        if (updated != 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_UPDATE_ERROR);
        }

        // 设置保存后只清租户详情缓存和 RPM 配置；当前分钟 TPM 已用量必须保留。
        stringRedisTemplate.delete(RedisKeyConstant.TENANT_INFO_KEY + tenantId);
        rateLimiter.invalidateTenantRpm(tenantId);

        TenantDO saved = requireActiveTenant(tenantId);
        return toResponse(saved, membership.getRole());
    }

    private UserTenantDO requireMembership(Long userId, Long tenantId) {
        if (userId == null) {
            throw new ClientException(TenantErrorCodeEnum.USER_ID_IS_NULL);
        }
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        UserTenantDO membership = userTenantMapper.selectOne(Wrappers.lambdaQuery(UserTenantDO.class)
                .eq(UserTenantDO::getUserId, userId)
                .eq(UserTenantDO::getTenantId, tenantId)
                .isNull(UserTenantDO::getLeftAt));
        if (membership == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        return membership;
    }

    private TenantDO requireActiveTenant(Long tenantId) {
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        return tenant;
    }

    private TenantSettingsRespDTO toResponse(TenantDO tenant, String role) {
        TenantSettingsRespDTO response = new TenantSettingsRespDTO();
        response.setTenantId(tenant.getTenantId());
        response.setType(tenant.getType());
        response.setName(tenant.getName());
        response.setDescription(tenant.getDescription());
        response.setJoinApprovalMode(tenant.getJoinApprovalMode());
        response.setAuditEnabled(Integer.valueOf(1).equals(tenant.getAuditEnabled()));
        response.setRateLimitRpm(tenant.getRateLimitRpm());
        response.setRateLimitTpm(tenant.getRateLimitTpm());
        response.setRole(role);
        response.setEditable(RoleEnum.valueOf(role).isAdmin());
        return response;
    }

    private Integer normalizeLimit(Integer value) {
        return value == null || value == 0 ? null : value;
    }
}
