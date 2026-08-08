package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantJoinRequestStatusEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.TenantService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private static final String CLOSE_TENANT_WARNING_DESCRIPTION = "关闭租户后将导致该租户下的所有数据无法访问且无法恢复，请谨慎操作。";
    private static final Map<String, String> ROLE_DISPLAY_MAP = Map.of(
            "SUPER_ADMIN", "超级管理员",
            "ADMIN", "管理员",
            "MEMBER", "成员"
    );

    private static final List<String> CLOSE_TENANT_WARNING_TIPS = List.of(
            "所有成员将无法进入该租户",
            "该租户下的所有数据将无法访问且无法恢复",
            "当前租户 API Key 将立即失效",
            "已创建的 LLM 服务不可访问",
            "无法查看该租户下的历史审计记录"
    );

    private static final String LEAVE_TENANT_WARNING_DESCRIPTION = "离开租户后将导致该租户下的所有数据无法访问，请谨慎操作。";
    private static final List<String> LEAVE_TENANT_WARNING_TIPS = List.of(
            "离开租户后将无法访问该租户下的所有数据",
            "离开租户后将无法访问该租户下的所有 LLM 服务",
            "离开租户后将无法访问该租户下的所有历史审计记录",
            "离开租户后将无法访问该租户下的所有 API Key",
            "您可以重新加入该租户，但需要管理员重新邀请您"
    );

    private final UserTenantService userTenantService;
    private final NotificationService notificationService;
    private final TenantInviteMapper tenantInviteMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RBloomFilter<String> inviteCodeFilter;
    private final JwtUtil jwtUtil;
    private final TenantJoinRequestMapper tenantJoinRequestMapper;
    private final NotificationMapper notificationMapper;

    @Value("${verse.tenant.max-invite-code-per-day:10}")
    private Integer maxInviteCodePerDay;

    @Value("${verse.frontend-baseurl}")
    private String frontendBaseUrl;

    @Value("${verse.tenant.max-notification-send-per-day:10}")
    private Integer maxNotificationSendPerDay;

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

    @Override
    public Long getPersonalTenantId(Long userId) {
        TenantDO tenantDO = baseMapper.selectOne(
                Wrappers.lambdaQuery(TenantDO.class)
                        .eq(TenantDO::getOwnerId, userId)
                        .eq(TenantDO::getType, "PERSONAL")
                        .eq(TenantDO::getStatus, 1)
                        .eq(TenantDO::getDelFlag, 0));
        return tenantDO != null ? tenantDO.getTenantId() : null;
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
    public TenantInfoRespDTO getTenantInfo(Long userId, Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        } else if (userId == null) {
            throw new ClientException(TenantErrorCodeEnum.USER_ID_IS_NULL);
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
        // 查询今天已经生成的邀请码数量，以自然日统计
        LambdaQueryWrapper<TenantInviteDO> queryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getTenantId, tenantId)
                .ge(TenantInviteDO::getCreateTime, getStartOfToday());
        Long count = tenantInviteMapper.selectCount(queryWrapper);
        if (count >= maxInviteCodePerDay) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_GENE_PER_DAY_LIMIT);
        }

        // 查询当前租户是否存在且活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.INVITE_CODE_CAN_NOT_GENE);
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

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
        resp.setInviteUrl(frontendBaseUrl + "/join/" + inviteCode);
        resp.setExpiresAt(requestParam.getExpireAt());
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantJoinRespDTO joinTenant(Long userId, TenantJoinReqDTO requestParam) {
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
        // 校验租户是否需要审批才能加入
        if (tenantDO.getJoinApprovalMode() == 0) {
            realJoinTenant(userId, tenantDO.getTenantId());
            // inviteCode使用次数+1
            inviteDO.setUsageCount(inviteDO.getUsageCount() + 1);
            if (tenantInviteMapper.updateById(inviteDO) < 1) {
                log.error("Update tenant invite code usage count error: tenant {}, user {}", tenantDO.getTenantId(), userId);
            }
            return new TenantJoinRespDTO(false);
        }
        // 需要审批才能加入
        // TODO 流程过长，做异步处理优化
        // 查询是否已经有PENDING申请
        TenantJoinRequestDO tenantJoinRequestDO = tenantJoinRequestMapper.selectOne(Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getUserId, userId)
                .eq(TenantJoinRequestDO::getTenantId, tenantDO.getTenantId())
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name()));
        if (tenantJoinRequestDO != null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_JOIN_REQUEST_PENDING_EXISTS);
        }
        // 创建一条申请
        TenantJoinRequestDO newRequest = new TenantJoinRequestDO();
        newRequest.setRequestId(SnowflakeIdUtil.nextId());
        newRequest.setTenantId(tenantDO.getTenantId());
        newRequest.setUserId(userId);
        newRequest.setInviteId(inviteDO.getId());
        newRequest.setStatus(TenantJoinRequestStatusEnum.PENDING.name());
        newRequest.setRequestedAt(new Date());
        int inserted = tenantJoinRequestMapper.insert(newRequest);
        if (inserted < 1) {
            log.error("Create tenant join request error: tenant {}, user {}", tenantDO.getTenantId(), userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_JOIN_REQUEST_CREATE_ERROR);
        }
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + newRequest.getRequestId(),
                JSON.toJSONString(newRequest), 30, TimeUnit.MINUTES);

        // 查询所有管理员，发送通知
        List<Long> adminIdList = userTenantService.getTenantAdmins(tenantDO.getTenantId())
                .stream().map(UserTenantDO::getUserId).toList();
        UserDO applicant = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .eq(UserDO::getDelFlag, 0));
        String applicantName = applicant != null ? applicant.getUsername() : String.valueOf(userId);
        try {
            notificationService.createAndPush(tenantDO.getTenantId(), "SYSTEM", "INFO",
                    "租户加入申请", "用户" + applicantName + "申请加入租户" + tenantDO.getName(),
                    null, adminIdList);
        } catch (Exception e) {
            log.error("Creating and pushing notification error for create request: {}", e.getMessage());
        }

        return new TenantJoinRespDTO(true);
    }

    @Override
    public TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode) {
        // 查询邀请码是否存在
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getCode, inviteCode));
        if (inviteDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }
        // 根据邀请码DO获取租户最基本信息
        Long tenantId = inviteDO.getTenantId();
        String name;

        String cacheKey = RedisKeyConstant.TENANT_INFO_KEY + tenantId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            TenantInfoRespDTO respDTO = JSON.parseObject(cachedJson, TenantInfoRespDTO.class);
            name = respDTO.getName();
        } else {
            TenantDO tenantDO = baseMapper.selectOne(
                    Wrappers.lambdaQuery(TenantDO.class)
                            .eq(TenantDO::getTenantId, tenantId)
                            .eq(TenantDO::getStatus, 1)
                            .eq(TenantDO::getDelFlag, 0));
            if (tenantDO == null) {
                throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
            }
            name = tenantDO.getName();
        }
        return new TenantJoinInfoRespDTO(name, inviteCode);
    }

    @Override
    public TenantLeavePrepareRespDTO prepareLeaveTenant(Long userId, Long tenantId) {
        // 检查租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.PERSONAL_TENANT_CAN_NOT_LEAVE);

        // 查询用户是否在该租户下
        Boolean isUserJoinTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isUserJoinTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        return new TenantLeavePrepareRespDTO(LEAVE_TENANT_WARNING_DESCRIPTION, LEAVE_TENANT_WARNING_TIPS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantLeaveRespDTO leaveTenant(Long userId, Long tenantId) {
        // 检查租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.PERSONAL_TENANT_CAN_NOT_LEAVE);

        // 查询用户是否在该租户下
        Boolean isUserJoinTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isUserJoinTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 如果该用户是超级管理员，则报错提示需要进行交接后才能离开租户
        String role = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        if (RoleEnum.SUPER_ADMIN.name().equals(role)) {
            throw new ClientException(TenantErrorCodeEnum.SUPER_ADMIN_LEAVE_TENANT_ERROR);
        }
        // 删除用户-租户关系
        userTenantService.removeUser(userId, tenantId);
        // 查找该用户对应的个人租户，返回个人租户的租户ID
        TenantDO tenantDO = baseMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getOwnerId, userId)
                .eq(TenantDO::getType, "PERSONAL"));
        return new TenantLeaveRespDTO(tenantDO.getTenantId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantSwitchRespDTO switchTenant(Long userId, Long tenantId) {
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        TenantInfoRespDTO respDTO = this.getTenantInfo(userId, tenantId);
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
            log.error("Switch Tenant Error While Update UserDO Table: tenant {}, user {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_SWITCH_ERROR);
        }

        userTenantService.switchTenant(userId, tenantId);
        TenantSwitchRespDTO resp = new TenantSwitchRespDTO();
        resp.setName(respDTO.getName());
        resp.setTenantId(tenantId);
        resp.setType(respDTO.getType());
        resp.setRole(userTenantService.getRoleByUserIdAndTenantId(userId, tenantId));
        return resp;
    }

    @Override
    public TenantClosePrepareRespDTO prepareCloseTenant(Long userId, Long tenantId) {
        // 检查租户是否存在and活跃
        LambdaQueryWrapper<TenantDO> queryWrapper = Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0);
        TenantDO tenantDO = baseMapper.selectOne(queryWrapper);
        if (tenantDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        } else if (!"TEAM".equals(tenantDO.getType())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CAN_NOT_CLOSE);
        }
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        TenantClosePrepareRespDTO respDTO = new TenantClosePrepareRespDTO();
        respDTO.setWarningDescription(CLOSE_TENANT_WARNING_DESCRIPTION);
        respDTO.setWarningTips(CLOSE_TENANT_WARNING_TIPS);

        String token = jwtUtil.generateCloseTenantToken(tenantId, userId, 5 * 60 * 1000L);
        Date expiresAt = new Date(System.currentTimeMillis() + 5 * 60 * 1000L);
        respDTO.setDisableToken(token);
        respDTO.setTokenExpireTime(expiresAt);

        String cacheKey = RedisKeyConstant.TENANT_CLOSE_TOKEN_KEY + tenantId + "-" + userId;
        stringRedisTemplate.opsForValue().set(cacheKey, token, 5, TimeUnit.MINUTES);

        return respDTO;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean closeTenant(Long userId, Long tenantId, TenantCloseReqDTO requestParam) {
        // TODO 未来关闭租户时需要做更多的操作，因此需要用MQ做异步关闭租户
        // 因为现在功能比较少，所以先不接MQ
        String disableToken = requestParam.getDisableToken();
        String cacheKey = RedisKeyConstant.TENANT_CLOSE_TOKEN_KEY + tenantId + "-" + userId;
        String cachedToken = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedToken == null || !cachedToken.equals(disableToken)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CLOSE_TOKEN_EXPIRED);
        }
        boolean validateResult = jwtUtil.validateToken(disableToken);
        if (!validateResult) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CLOSE_TOKEN_EXPIRED);
        }

        // 检查租户是否存在and活跃
        LambdaQueryWrapper<TenantDO> queryWrapper = Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0);
        TenantDO tenantDO = baseMapper.selectOne(queryWrapper);
        if (tenantDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        } else if (!"TEAM".equals(tenantDO.getType())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CAN_NOT_CLOSE);
        }
        String confirmText = requestParam.getConfirmText();
        if (!tenantDO.getName().equals(confirmText)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NAME_ERROR);
        }

        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        // 关闭租户
        LambdaUpdateWrapper<TenantDO> updateWrapper = Wrappers.lambdaUpdate(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0)
                .set(TenantDO::getStatus, 0);
        int update = baseMapper.update(updateWrapper);
        if (update < 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_CLOSE_ERROR);
        }
        // 清理该租户下所有用户-租户关系的Redis缓存
        List<UserTenantDO> userTenants = userTenantService.list(
                Wrappers.lambdaQuery(UserTenantDO.class)
                        .eq(UserTenantDO::getTenantId, tenantId)
                        .isNull(UserTenantDO::getLeftAt));
        List<String> deleteKeys = userTenants.stream()
                .map(ut -> RedisKeyConstant.USER_TENANT_RELATION_KEY + ut.getUserId() + ":" + tenantId)
                .toList();
        stringRedisTemplate.delete(deleteKeys);

        // 删除租户信息缓存
        String tenantCacheKey = RedisKeyConstant.TENANT_INFO_KEY + tenantId;
        stringRedisTemplate.delete(tenantCacheKey);

        // 通知租户内所有成员
        try {
            List<Long> recipientIds = userTenants.stream()
                    .map(UserTenantDO::getUserId).toList();
            notificationService.createAndPush(
                    tenantId, "SYSTEM", "CRITICAL",
                    "租户已停用",
                    "您所在的租户「" + tenantDO.getName() + "」已被管理员停用",
                    null, recipientIds);
        } catch (Exception e) {
            log.error("[notification] 租户停用通知推送失败: tenantId={}", tenantId, e);
        }

        return Boolean.TRUE;
    }

    @Override
    public TenantMembersListRespDTO listTenantMembers(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_CAN_NOT_LIST_MEMBERS);
        // 检查用户是否在该租户下
        if (!userTenantService.isUserJoinedTenant(userId, tenantId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        // 分页查询租户成员列表
        Page<UserTenantDO> pageResult = userTenantService.page(
                new Page<>(pageNum, pageSize),
                Wrappers.lambdaQuery(UserTenantDO.class)
                        .eq(UserTenantDO::getTenantId, tenantId)
                        .isNull(UserTenantDO::getLeftAt)
                        .orderByAsc(UserTenantDO::getJoinedAt));

        // 批量查询成员的用户信息，避免 N+1
        List<Long> userIds = pageResult.getRecords().stream()
                .map(UserTenantDO::getUserId)
                .distinct()
                .toList();
        Map<Long, UserDO> userMap = userIds.isEmpty() ? Map.of() : userMapper.selectList(
                Wrappers.lambdaQuery(UserDO.class).in(UserDO::getUserId, userIds))
                .stream()
                .collect(Collectors.toMap(UserDO::getUserId, u -> u));

        List<TenantMembersListRespDTO.TenantMemberInfo> respList = pageResult.getRecords().stream()
                .map(ut -> {
                    TenantMembersListRespDTO.TenantMemberInfo memberInfo = new TenantMembersListRespDTO.TenantMemberInfo();
                    memberInfo.setUserId(ut.getUserId());
                    memberInfo.setRole(ut.getRole());
                    memberInfo.setJoinedAt(ut.getJoinedAt());
                    UserDO userDO = userMap.get(ut.getUserId());
                    if (userDO != null) {
                        memberInfo.setUsername(userDO.getUsername());
                        memberInfo.setNickname(userDO.getNickname());
                    }
                    return memberInfo;
                })
                .toList();

        return new TenantMembersListRespDTO(respList, pageResult.getTotal(), pageResult.getPages(), pageNum, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateMemberRole(Long userId, Long tenantId, Long memberId, TenantMemberRoleUpdateReqDTO requestParam) {
        TenantDO tenantDO = validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE);
        // 检查用户是否在该租户下
        Boolean userJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!userJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 检查成员是否在该租户下
        Boolean memberJoinedTenant = userTenantService.isUserJoinedTenant(memberId, tenantId);
        if (!memberJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_NOT_JOINED);
        }
        // 更新成员角色
        // 原则1：更新的最终角色不能高于操作人的角色，例如操作人是ADMIN，不能把成员更新为SUPER_ADMIN
        // 原则2：不能更新比自己角色高或者相等的成员，例如操作人是ADMIN，不能更新SUPER_ADMIN和ADMIN的角色
        String operatorRole = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        String memberRole = userTenantService.getRoleByUserIdAndTenantId(memberId, tenantId);
        if (RoleEnum.valueOf(memberRole).isNotLowerThan(RoleEnum.valueOf(operatorRole))) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE);
        }
        String targetRole = requestParam.getNewRole();
        if (RoleEnum.valueOf(targetRole).isNotLowerThan(RoleEnum.valueOf(operatorRole))) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE);
        }
        userTenantService.updateUserRole(memberId, tenantId, memberRole, targetRole);

        // 通知被变更者
        try {
            String oldRoleName = ROLE_DISPLAY_MAP.getOrDefault(memberRole, memberRole);
            String newRoleName = ROLE_DISPLAY_MAP.getOrDefault(targetRole, targetRole);
            notificationService.createAndPush(
                    tenantId, "SYSTEM", "INFO",
                    "角色已变更",
                    "您在租户「" + tenantDO.getName() + "」中的角色已被管理员从" + oldRoleName + "变更为" + newRoleName,
                    null, List.of(memberId));
        } catch (Exception e) {
            log.error("[notification] 角色变更通知推送失败: tenantId={}, memberId={}", tenantId, memberId, e);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeMember(Long userId, Long tenantId, Long memberId) {
        TenantDO tenantDO = validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE);
        // 检查用户是否在该租户下
        Boolean userJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!userJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 检查成员是否在该租户下
        Boolean memberJoinedTenant = userTenantService.isUserJoinedTenant(memberId, tenantId);
        if (!memberJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_NOT_JOINED);
        }
        // 原则：不能移除比自己角色高或者相等的成员，例如操作人是ADMIN，不能移除SUPER_ADMIN和ADMIN的成员
        String operatorRole = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        String memberRole = userTenantService.getRoleByUserIdAndTenantId(memberId, tenantId);
        if (RoleEnum.valueOf(memberRole).isNotLowerThan(RoleEnum.valueOf(operatorRole))) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE);
        }
        userTenantService.removeUser(memberId, tenantId);

        // 通知被移除者
        try {
            notificationService.createAndPush(
                    tenantId, "SYSTEM", "WARNING",
                    "已被移出租户",
                    "您已被管理员移出租户「" + tenantDO.getName() + "」",
                    null, List.of(memberId));
        } catch (Exception e) {
            log.error("[notification] 移除成员通知推送失败: tenantId={}, memberId={}", tenantId, memberId, e);
        }

        return Boolean.TRUE;
    }

    @Override
    public TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        // 检查租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 检查用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        // 查询租户邀请码列表，按照创建时间倒序排序，分页查询
        // 查询生效的所有邀请码，只要不过期的都能查到
        Date now = new Date();
        Page<TenantInviteListRespDTO.TenantInviteInfo> page = tenantInviteMapper.selectPageByTenantId(new Page<>(pageNum, pageSize), tenantId, now);
        List<TenantInviteListRespDTO.TenantInviteInfo> records = page.getRecords();
        if (!records.isEmpty()) {
            // 设置inviteUrl字段
            records.forEach(record -> {
                String inviteUrl = frontendBaseUrl + "/join/" + record.getCode();
                record.setInviteUrl(inviteUrl);
            });
        }
        TenantInviteListRespDTO resp = new TenantInviteListRespDTO();
        resp.setInviteCodes(records);
        resp.setTotal(page.getTotal());
        resp.setTotalPages(page.getPages());
        resp.setPage(pageNum);
        resp.setPageSize(pageSize);

        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deactivateInviteCode(Long userId, Long tenantId, Long inviteCodeId) {
        // 检查租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 检查用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        // 查询邀请码是否存在且属于该租户，且该邀请码是活跃的
        LambdaQueryWrapper<TenantInviteDO> queryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, inviteCodeId)
                .eq(TenantInviteDO::getTenantId, tenantId);
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(queryWrapper);
        if (inviteDO == null) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_NOT_FOUND);
        } else if (inviteDO.getIsActive() == 0) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_CAN_NOT_DEACTIVATE);
        } else if (inviteDO.getExpiresAt() != null && inviteDO.getExpiresAt().before(new Date())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }

        // 将邀请码设置为不活跃
        LambdaUpdateWrapper<TenantInviteDO> updateWrapper = Wrappers.lambdaUpdate(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, inviteCodeId)
                .eq(TenantInviteDO::getTenantId, tenantId)
                .set(TenantInviteDO::getIsActive, 0);
        int update = tenantInviteMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Deactivate Invite Code Error: tenant {}, inviteCodeId {}", tenantId, inviteCodeId);
            throw new ServerException(TenantErrorCodeEnum.INVITE_CODE_DEACTIVATE_ERROR);
        }
        // 删除Redis缓存
        String cacheKey = RedisKeyConstant.TENANT_INVITE_CODE_KEY + inviteDO.getCode();
        stringRedisTemplate.delete(cacheKey);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean activateInviteCode(Long userId, Long tenantId, Long inviteCodeId) {
        // 检查租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 检查用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        // 查询邀请码是否存在且属于该租户，且该邀请码是非活跃的
        LambdaQueryWrapper<TenantInviteDO> queryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, inviteCodeId)
                .eq(TenantInviteDO::getTenantId, tenantId);
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(queryWrapper);
        if (inviteDO == null) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_NOT_FOUND);
        } else if (inviteDO.getIsActive() == 1) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_CAN_NOT_ACTIVATE);
        } else if (inviteDO.getExpiresAt() != null && inviteDO.getExpiresAt().before(new Date())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }

        // 将邀请码设置为活跃
        LambdaUpdateWrapper<TenantInviteDO> updateWrapper = Wrappers.lambdaUpdate(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, inviteCodeId)
                .eq(TenantInviteDO::getTenantId, tenantId)
                .set(TenantInviteDO::getIsActive, 1);
        int update = tenantInviteMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Activate Invite Code Error: tenant {}, inviteCodeId {}", tenantId, inviteCodeId);
            throw new ServerException(TenantErrorCodeEnum.INVITE_CODE_ACTIVATE_ERROR);
        }
        // 重新写回到缓存
        inviteDO.setIsActive(1);
        String cacheKey = RedisKeyConstant.TENANT_INVITE_CODE_KEY + inviteDO.getCode();
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(inviteDO), 15, TimeUnit.MINUTES);
        return Boolean.TRUE;
    }

    @Override
    public TenantJoinReqListRespDTO listJoinRequests(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        if (pageSize == null) {
            pageSize = 10;
        }
        // 查询租户是否存在且活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 查询租户加入请求列表，根据请求时间倒序分页查询
        // TODO 按照状态进行排序
        Page<TenantJoinReqListRespDTO.TenantJoinReqInfo> pages = tenantJoinRequestMapper.selectPageByTenantId(new Page<>(pageNum, pageSize), tenantId);
        List<TenantJoinReqListRespDTO.TenantJoinReqInfo> records = pages.getRecords();
        TenantJoinReqListRespDTO resp = new TenantJoinReqListRespDTO();
        resp.setRequestList(records);
        resp.setTotal(pages.getTotal());
        resp.setTotalPages(pages.getPages());
        resp.setPage(pageNum);
        resp.setPageSize(pageSize);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean approveJoinRequest(Long userId, Long tenantId, Long requestId) {
        // TODO 流程过长，非与审批有关的操作做异步处理
        // 查询租户是否存在且活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        TenantJoinRequestDO requestDO = validateJoinRequest(userId, requestId);
        // 修改申请状态为已批准
        LambdaUpdateWrapper<TenantJoinRequestDO> updateWrapper = Wrappers.lambdaUpdate(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name())
                .set(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.APPROVED.name())
                .set(TenantJoinRequestDO::getReviewedAt, new Date())
                .set(TenantJoinRequestDO::getReviewedBy, userId);
        int update = tenantJoinRequestMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Approve Join Request Error: tenant {}, requestId {}", tenantId, requestId);
            throw new ServerException(TenantErrorCodeEnum.REQUEST_STATUS_UPDATE_ERROR);
        }
        // 将申请人加入租户
        realJoinTenant(requestDO.getUserId(), tenantId);
        // 邀请码使用次数+1
        LambdaQueryWrapper<TenantInviteDO> inviteQueryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, requestDO.getInviteId());
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(inviteQueryWrapper);
        if (inviteDO != null) {
            inviteDO.setUsageCount(inviteDO.getUsageCount() + 1);
            if (tenantInviteMapper.updateById(inviteDO) < 1) {
                // 不抛异常以免影响主流程
                log.error("Update Invite Code Usage Count Error: tenant {}, inviteId {}", tenantId, requestDO.getInviteId());
            }
        }
        // 调用通知服务，通知申请人已被批准加入租户
        TenantInfoRespDTO tenantInfo = getTenantInfo(userId, tenantId);
        try {
            notificationService.createAndPush(tenantId, "SYSTEM", "INFO",
                    "加入租户申请已批准",
                    "您加入租户「" + tenantInfo.getName() + "」的申请已被管理员批准",
                    null, List.of(requestDO.getUserId()));
        } catch (Exception e) {
            log.error("Creating and pushing notification error for approve: {}", e.getMessage());
        }
        // 删除缓存
        stringRedisTemplate.delete(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean rejectJoinRequest(Long userId, Long tenantId, Long requestId, TenantJoinRejectReqDTO requestParam) {
        // 查询租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        TenantJoinRequestDO tenantJoinRequestDO = validateJoinRequest(userId, requestId);
        // 修改申请状态为已拒绝
        int update = tenantJoinRequestMapper.update(Wrappers.lambdaUpdate(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getRequestId, requestId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name())
                .set(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.REJECTED.name())
                .set(TenantJoinRequestDO::getReviewedAt, new Date())
                .set(TenantJoinRequestDO::getReviewedBy, userId)
                .set(TenantJoinRequestDO::getReviewComment, requestParam.getReviewComment()));
        if (update < 1) {
            log.error("Reject Join Request Error: tenant {}, requestId {}", tenantId, requestId);
            throw new ServerException(TenantErrorCodeEnum.REQUEST_STATUS_UPDATE_ERROR);
        }
        TenantInfoRespDTO tenantInfo = getTenantInfo(userId, tenantId);
        try {
            notificationService.createAndPush(tenantId, "SYSTEM", "INFO",
                    "申请被拒绝",
                    requestParam.getReviewComment() == null ? "您加入" + tenantInfo.getName() + "的申请已被管理员拒绝" : "您加入" + tenantInfo.getName() + "的申请已被管理员拒绝，理由：" + requestParam.getReviewComment(),
                    null, List.of(tenantJoinRequestDO.getUserId()));
        } catch (Exception e) {
            log.error("Creating and pushing notification error for reject: {}", e.getMessage());
        }
        // 删除缓存
        stringRedisTemplate.delete(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);

        return Boolean.TRUE;
    }

    @Override
    public Long getUnreviewedJoinReqCount(Long userId, Long tenantId) {
        // 查询租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 查询用户是否在该租户下
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 查询该租户下所有未审批的申请单数量
        return tenantJoinRequestMapper.selectCount(Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                .eq(TenantJoinRequestDO::getTenantId, tenantId)
                .eq(TenantJoinRequestDO::getStatus, TenantJoinRequestStatusEnum.PENDING.name()));
    }

    @Override
    public Boolean sendNotificationInTenant(Long userId, Long tenantId, TenantSendNotificationReqDTO requestParam) {
        // 租户是否存在and是否活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        // 用户是否还在此租户
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 判断今日内该租户已发送的通知数量是否超过限额
        LambdaQueryWrapper<NotificationDO> queryWrapper = Wrappers.lambdaQuery(NotificationDO.class)
                .eq(NotificationDO::getTenantId, tenantId)
                .eq(NotificationDO::getType, "ANNOUNCEMENT")
                .isNotNull(NotificationDO::getSenderId)
                .ge(NotificationDO::getCreateTime, getStartOfToday());
        Long count = notificationMapper.selectCount(queryWrapper);
        if (count >= maxNotificationSendPerDay) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOTIFICATION_SEND_PER_DAY_LIMIT);
        }
        // 根据接收者类型，获取接收者的ID列表
        Integer receiverType = requestParam.getReceiverType();
        List<Long> receiverIdList = new ArrayList<>();
        if (receiverType == 1) {
            // 全员
            receiverIdList = userTenantService.getTenantAllMembers(tenantId).stream().map(UserTenantDO::getUserId).toList();
        } else if (receiverType == 2) {
            // 只有MEMBER
            receiverIdList = userTenantService.getTenantMembers(tenantId).stream().map(UserTenantDO::getUserId).toList();
        } else if (receiverType == 3) {
            // 只有管理
            receiverIdList = userTenantService.getTenantAdmins(tenantId).stream().map(UserTenantDO::getUserId).toList();
        }

        // 调用通知服务的发送接口
        try {
            notificationService.createAndPush(tenantId, "ANNOUNCEMENT", requestParam.getSeverity(),
                    requestParam.getTitle(), requestParam.getContent(), userId, receiverIdList);
        } catch (Exception e) {
            log.error("Sending notification in tenant error: tenant {}", tenantId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_NOTIFICATION_PUSH_ERROR);
        }
        return Boolean.TRUE;
    }

    private TenantDO validateTenantTeamActive(Long tenantId, TenantErrorCodeEnum notTeamError) {
        TenantDO tenantDO = baseMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenantDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        if (!"TEAM".equals(tenantDO.getType())) {
            throw new ClientException(notTeamError);
        }
        return tenantDO;
    }

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private Date getStartOfToday() {
        return Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private void realJoinTenant(Long userId, Long tenantId) {
        Boolean result = userTenantService.createUserTenant(userId, tenantId, RoleEnum.MEMBER.name());
        if (!result) {
            log.error("Join Tenant Error: tenant {}, user {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_JOIN_ERROR);
        }
    }

    private TenantJoinRequestDO validateJoinRequest(Long userId, Long requestId) {
        TenantJoinRequestDO requestDO;
        // 查询申请单是否存在
        String cachedJson = stringRedisTemplate.opsForValue().get(RedisKeyConstant.TENANT_JOIN_REQUEST_KEY + requestId);
        if (cachedJson != null) {
            requestDO = JSON.parseObject(cachedJson, TenantJoinRequestDO.class);
        } else {
            LambdaQueryWrapper<TenantJoinRequestDO> queryWrapper = Wrappers.lambdaQuery(TenantJoinRequestDO.class)
                    .eq(TenantJoinRequestDO::getRequestId, requestId);
            requestDO = tenantJoinRequestMapper.selectOne(queryWrapper);
        }
        if (requestDO == null) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_NOT_FOUND);
        }
        // 申请人和审批人是否为同一人
        if (requestDO.getUserId().equals(userId)) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_APPROVE_SELF_ERROR);
        }
        // 申请单状态是否为待审批
        if (!TenantJoinRequestStatusEnum.PENDING.name().equals(requestDO.getStatus())) {
            throw new ClientException(TenantErrorCodeEnum.REQUEST_HAS_BEEN_REVIEWED);
        }
        return requestDO;
    }
}
