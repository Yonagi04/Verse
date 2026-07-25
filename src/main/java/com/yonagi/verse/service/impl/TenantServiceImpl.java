package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.TenantCreateReqDTO;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
import com.yonagi.verse.service.TenantService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 20:52
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantServiceImpl extends ServiceImpl<TenantMapper, TenantDO> implements TenantService {

    private final UserTenantService userTenantService;
    private final UserMapper userMapper;

    @Override
    public List<TenantInfoListRespDTO> listTenants(Long userId) {
        // TODO 待实现
        return List.of();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createTenant(Long userId, TenantCreateReqDTO requestParam) {
        String tenantName = requestParam.getName();

        Long tenantId = doCreateTenant(userId, tenantName, "TEAM", requestParam.getDescription());

        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .set(UserDO::getLastActiveTenantId, tenantId);
        userMapper.update(updateWrapper);

        return Boolean.TRUE;
    }

    @Override
    public Long createPersonalTenant(Long userId, String tenantName) {
        return doCreateTenant(userId, tenantName, "PERSONAL", null);
    }

    private Long doCreateTenant(Long userId, String name, String type, String description) {
        Long tenantId = SnowflakeIdUtil.nextId();
        TenantDO tenantDO = new TenantDO();
        tenantDO.setTenantId(tenantId);
        tenantDO.setName(name);
        tenantDO.setType(type);
        tenantDO.setOwnerId(userId);
        tenantDO.setDescription(description);
        tenantDO.setStatus(1);
        int tenantInserted = baseMapper.insert(tenantDO);
        if (tenantInserted < 1) {
            log.error("Create tenant failed, tenantName: {}, type: {}, userId: {}", name, type, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_CREATE_ERROR);
        }

        userTenantService.createUserTenant(userId, tenantId);

        return tenantId;
    }

    @Override
    public TenantInfoRespDTO getTenantInfo(Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        TenantDO tenantDO = baseMapper.selectOne(
                Wrappers.lambdaQuery(TenantDO.class)
                        .eq(TenantDO::getTenantId, tenantId));
        if (tenantDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        TenantInfoRespDTO resp = new TenantInfoRespDTO();
        BeanUtil.copyProperties(tenantDO, resp);
        return resp;
    }
}
