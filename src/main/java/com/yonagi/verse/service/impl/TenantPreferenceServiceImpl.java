package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.req.TenantPreferenceReqDTO;
import com.yonagi.verse.dto.resp.TenantPreferenceRespDTO;
import com.yonagi.verse.service.TenantPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 偏好保存在用户与租户的成员关系中，所有设备读取同一权威值。 */
@Service
@RequiredArgsConstructor
public class TenantPreferenceServiceImpl implements TenantPreferenceService {
    private final UserTenantMapper userTenantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantPreferenceRespDTO update(Long userId, Long tenantId, TenantPreferenceReqDTO request) {
        if (tenantId == null) throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        UserTenantDO membership = userTenantMapper.selectActiveMembership(userId, tenantId);
        if (membership == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        boolean favorite = Boolean.TRUE.equals(request.getFavorite());
        boolean pinned = Boolean.TRUE.equals(request.getPinned());
        if (favorite != Boolean.TRUE.equals(membership.getFavorite())
                || pinned != Boolean.TRUE.equals(membership.getPinned())) {
            if (userTenantMapper.updatePreference(userId, tenantId, favorite, pinned) < 1) {
                throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
            }
        }
        TenantPreferenceRespDTO result = new TenantPreferenceRespDTO();
        result.setTenantId(tenantId);
        result.setFavorite(favorite);
        result.setPinned(pinned);
        return result;
    }
}
