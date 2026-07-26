package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantInviteMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.TenantCreateReqDTO;
import com.yonagi.verse.dto.req.TenantInviteReqDTO;
import com.yonagi.verse.dto.req.TenantJoinReqDTO;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
import com.yonagi.verse.dto.resp.TenantInviteRespDTO;
import com.yonagi.verse.service.TenantService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final Integer INVITE_CODE_LENGTH = 8;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserTenantService userTenantService;
    private final TenantInviteMapper tenantInviteMapper;
    private final UserMapper userMapper;

    @Override
    public List<TenantInfoListRespDTO> listTenants(Long userId) {
        List<UserTenantDO> userTenants = userTenantService.getUserTenantList(userId, Boolean.FALSE, 10L);
        if (userTenants.isEmpty()) {
            return List.of();
        }

        List<Long> tenantIds = userTenants.stream()
                .map(UserTenantDO::getTenantId)
                .toList();

        Map<Long, TenantDO> tenantMap = baseMapper.selectList(
                Wrappers.lambdaQuery(TenantDO.class)
                        .in(TenantDO::getTenantId, tenantIds))
                .stream()
                .collect(Collectors.toMap(TenantDO::getTenantId, t -> t));
        return userTenants.stream()
                .map(ut -> {
                    TenantDO tenant = tenantMap.get(ut.getTenantId());
                    TenantInfoListRespDTO dto = new TenantInfoListRespDTO();
                    dto.setTenantId(ut.getTenantId());
                    dto.setName(tenant != null ? tenant.getName() : null);
                    dto.setType(tenant != null ? tenant.getType() : null);
                    dto.setRole(ut.getRole());
                    dto.setJoinedAt(ut.getJoinedAt());
                    dto.setLastAccessedAt(ut.getLastAccessedAt());
                    return dto;
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createTenant(Long userId, TenantCreateReqDTO requestParam) {
        // TODO 普通用户最多加入10个租户（算上个人租户），未来如果有增值功能再增加
        Long joinedTenantCount = userTenantService.getUserJoinedTenantCount(userId);
        if (joinedTenantCount >= 10) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_COUNT_EXCEEDS);
        }

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

        Boolean createUserTenantResult = userTenantService.createUserTenant(userId, tenantId, RoleEnum.SUPER_ADMIN.name());
        if (createUserTenantResult.equals(Boolean.FALSE)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CREATE_ERROR);
        }
        return tenantId;
    }

    @Override
    public TenantInfoRespDTO getTenantInfo(Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        TenantDO tenantDO = baseMapper.selectOne(
                Wrappers.lambdaQuery(TenantDO.class)
                        .eq(TenantDO::getTenantId, tenantId)
                        .eq(TenantDO::getStatus, 1)
                        .eq(TenantDO::getDelFlag, 0));
        if (tenantDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        TenantInfoRespDTO resp = new TenantInfoRespDTO();
        BeanUtil.copyProperties(tenantDO, resp);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantInviteRespDTO inviteUser(Long userId, Long tenantId, TenantInviteReqDTO requestParam) {
        // 权限控制由 PreAuthorize 注解来管理，服务层不关心
        // 生成一个8位大写字母+数字组合的邀请码
        String inviteCode = generateInviteCode();
        TenantInviteDO inviteDO = new TenantInviteDO();
        inviteDO.setCode(inviteCode);
        inviteDO.setTenantId(tenantId);
        inviteDO.setCreatedBy(userId);
        inviteDO.setCreateTime(new Date());
        inviteDO.setIsActive(1);
        inviteDO.setExpiresAt(requestParam.getExpireAt());
        int insert = tenantInviteMapper.insert(inviteDO);
        if (insert < 1) {
            log.error("Create tenant invite code error: tenant {}, user {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_INVITE_CODE_CREATE_ERROR);
        }

        TenantInviteRespDTO resp = new TenantInviteRespDTO();
        resp.setInviteCode(inviteCode);
        resp.setExpiresAt(requestParam.getExpireAt());
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean joinTenant(Long userId, TenantJoinReqDTO requestParam) {
        Long joinedTenantCount = userTenantService.getUserJoinedTenantCount(userId);
        if (joinedTenantCount >= 10) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_COUNT_EXCEEDS);
        }
        // 租户邀请码是否过期
        String inviteCode = requestParam.getInviteCode();
        LambdaQueryWrapper<TenantInviteDO> queryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getCode, inviteCode);
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(queryWrapper);
        if (inviteDO == null || inviteDO.getIsActive() == 0) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }
        if (inviteDO.getExpiresAt() != null && inviteDO.getExpiresAt().before(new Date())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }

        // 用户是否已经在该租户内，以及租户类型是否为TEAM
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, inviteDO.getTenantId());
        if (isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_HAS_BEEN_JOINED);
        }
        TenantDO tenantDO = baseMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, inviteDO.getTenantId())
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenantDO == null || !"TEAM".equals(tenantDO.getType())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_JOIN_PROHIBITED);
        }

        Boolean result = userTenantService.createUserTenant(userId, tenantDO.getTenantId(), RoleEnum.MEMBER.name());
        if (!result) {
            log.error("Join Tenant Error: tenant {}, user {}", tenantDO.getTenantId(), userId);
            throw new ClientException(TenantErrorCodeEnum.TENANT_JOIN_ERROR);
        }
        return Boolean.TRUE;
    }

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
