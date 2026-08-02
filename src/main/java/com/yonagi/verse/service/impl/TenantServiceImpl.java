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
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantInviteMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
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

    @Value("${verse.tenant.max-invite-code-per-day:10}")
    private Integer maxInviteCodePerDay;

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
    public Boolean leaveTenant(Long userId, Long tenantId) {
        // 检查租户是否存在and活跃
        validateTenantTeamActive(tenantId, TenantErrorCodeEnum.PERSONAL_TENANT_CAN_NOT_LEAVE);

        // 查询用户是否在该租户下
        Boolean isUserJoinTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isUserJoinTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        // 删除用户-租户关系
        userTenantService.removeUser(userId, tenantId);
        return Boolean.TRUE;
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
        Date now = new Date();
        return new Date(now.getYear(), now.getMonth(), now.getDate(), 0, 0, 0);
    }
}
