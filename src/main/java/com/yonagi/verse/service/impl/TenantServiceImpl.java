package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
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
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
import com.yonagi.verse.dto.resp.TenantInviteRespDTO;
import com.yonagi.verse.dto.resp.TenantSwitchRespDTO;
import com.yonagi.verse.service.TenantService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    private final StringRedisTemplate stringRedisTemplate;
    private final RBloomFilter<String> inviteCodeFilter;

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
    public Boolean updateTenant(Long userId, Long tenantId, TenantUpdateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 更新租户
        LambdaUpdateWrapper<TenantDO> updateWrapper = Wrappers.lambdaUpdate(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0)
                .set(TenantDO::getName, requestParam.getName());
        if (StrUtil.isNotBlank(requestParam.getDescription())) {
            updateWrapper.set(TenantDO::getDescription, requestParam.getDescription());
        } else if (requestParam.getDescription() != null) {
            updateWrapper.set(TenantDO::getDescription, null);
        }

        int update = baseMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Update tenant failed, tenantId: {}, userId: {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_UPDATE_ERROR);
        }
        // 更新后删除缓存
        String cacheKey = RedisKeyConstant.TENANT_INFO_KEY + tenantId;
        stringRedisTemplate.delete(cacheKey);
        return Boolean.TRUE;
    }

    @Override
    public TenantInfoRespDTO getTenantInfo(Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        String cacheKey = RedisKeyConstant.TENANT_INFO_KEY + tenantId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            return JSON.parseObject(cachedJson, TenantInfoRespDTO.class);
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
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(resp), 30, TimeUnit.MINUTES);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantInviteRespDTO inviteUser(Long userId, Long tenantId, TenantInviteReqDTO requestParam) {
        // 权限控制由 PreAuthorize 注解来管理，服务层不关心
        // 生成一个8位大写字母+数字组合的邀请码
        String inviteCode = generateInviteCode();
        if (inviteCodeFilter.contains(inviteCode)) {
            // 如果发生了重复，就连续重试3次，3次都重复就直接抛异常
            boolean successGenerate = false;
            for (int i = 0; i < 3; i++) {
                inviteCode = generateInviteCode();
                if (!inviteCodeFilter.contains(inviteCode)) {
                    successGenerate = true;
                    break;
                }
            }
            if (!successGenerate) {
                throw new ServerException(TenantErrorCodeEnum.TENANT_INVITE_CODE_CREATE_ERROR);
            }
        }

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
        String cacheKey = RedisKeyConstant.TENANT_INVITE_CODE_KEY + inviteCode;
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(inviteDO), 15, TimeUnit.MINUTES);
        inviteCodeFilter.add(inviteCode);

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
        String cachedJson = stringRedisTemplate.opsForValue().get(RedisKeyConstant.TENANT_INVITE_CODE_KEY + inviteCode);
        TenantInviteDO inviteDO;
        if (cachedJson != null) {
            inviteDO = JSON.parseObject(cachedJson, TenantInviteDO.class);
        } else {
            LambdaQueryWrapper<TenantInviteDO> queryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                    .eq(TenantInviteDO::getCode, inviteCode);
            inviteDO = tenantInviteMapper.selectOne(queryWrapper);
        }
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantSwitchRespDTO switchTenant(Long userId, Long tenantId) {
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        TenantInfoRespDTO respDTO = this.getTenantInfo(tenantId);
        if (respDTO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .eq(UserDO::getStatus, 1)
                .eq(UserDO::getDelFlag, 0)
                .set(UserDO::getLastActiveTenantId, tenantId);
        int update = userMapper.update(updateWrapper);
        if (update < 1) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_SWITCH_ERROR);
        }

        userTenantService.switchTenant(userId, tenantId);
        TenantSwitchRespDTO resp = new TenantSwitchRespDTO();
        resp.setName(respDTO.getName());
        resp.setTenantId(tenantId);
        resp.setType(respDTO.getType());
        resp.setRole(userTenantService.getRoleByUserIdAndTenantId(userId, tenantId));
        return resp;
    }

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
