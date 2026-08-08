package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.TenantCrudService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantCrudServiceImpl implements TenantCrudService {

    private static final String CLOSE_TENANT_WARNING_DESCRIPTION = "关闭租户后将导致该租户下的所有数据无法访问且无法恢复，请谨慎操作。";
    private static final List<String> CLOSE_TENANT_WARNING_TIPS = List.of(
            "所有成员将无法进入该租户",
            "该租户下的所有数据将无法访问且无法恢复",
            "当前租户 API Key 将立即失效",
            "已创建的 LLM 服务不可访问",
            "无法查看该租户下的历史审计记录"
    );

    private final TenantMapper tenantMapper;
    private final UserTenantService userTenantService;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtil jwtUtil;
    private final NotificationService notificationService;
    private final TenantValidationHelper validationHelper;
    private final NotificationMapper notificationMapper;

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

        Map<Long, TenantDO> tenantMap = tenantMapper.selectList(
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
        TenantDO tenantDO = tenantMapper.selectOne(
                Wrappers.lambdaQuery(TenantDO.class)
                        .eq(TenantDO::getOwnerId, userId)
                        .eq(TenantDO::getType, "PERSONAL")
                        .eq(TenantDO::getStatus, 1)
                        .eq(TenantDO::getDelFlag, 0));
        return tenantDO != null ? tenantDO.getTenantId() : null;
    }

    @Override
    public Boolean updateTenant(Long userId, Long tenantId, TenantUpdateReqDTO requestParam) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
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

        int update = tenantMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Update tenant failed, tenantId: {}, userId: {}", tenantId, userId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_UPDATE_ERROR);
        }
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

        TenantDO tenantDO = tenantMapper.selectOne(
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
    public TenantClosePrepareRespDTO prepareCloseTenant(Long userId, Long tenantId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_CAN_NOT_CLOSE);
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean closeTenant(Long userId, Long tenantId, TenantCloseReqDTO requestParam) {
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

        TenantDO tenantDO = validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_CAN_NOT_CLOSE);
        String confirmText = requestParam.getConfirmText();
        if (!tenantDO.getName().equals(confirmText)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NAME_ERROR);
        }

        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        LambdaUpdateWrapper<TenantDO> updateWrapper = Wrappers.lambdaUpdate(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0)
                .set(TenantDO::getStatus, 0);
        int update = tenantMapper.update(updateWrapper);
        if (update < 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_CLOSE_ERROR);
        }
        List<UserTenantDO> userTenants = userTenantService.list(
                Wrappers.lambdaQuery(UserTenantDO.class)
                        .eq(UserTenantDO::getTenantId, tenantId)
                        .isNull(UserTenantDO::getLeftAt));
        List<String> deleteKeys = userTenants.stream()
                .map(ut -> RedisKeyConstant.USER_TENANT_RELATION_KEY + ut.getUserId() + ":" + tenantId)
                .toList();
        stringRedisTemplate.delete(deleteKeys);

        String tenantCacheKey = RedisKeyConstant.TENANT_INFO_KEY + tenantId;
        stringRedisTemplate.delete(tenantCacheKey);

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
    public Boolean sendNotificationInTenant(Long userId, Long tenantId, TenantSendNotificationReqDTO requestParam) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        LambdaQueryWrapper<NotificationDO> queryWrapper = Wrappers.lambdaQuery(NotificationDO.class)
                .eq(NotificationDO::getTenantId, tenantId)
                .eq(NotificationDO::getType, "ANNOUNCEMENT")
                .isNotNull(NotificationDO::getSenderId)
                .ge(NotificationDO::getCreateTime, getStartOfToday());
        Long count = notificationMapper.selectCount(queryWrapper);
        if (count >= maxNotificationSendPerDay) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOTIFICATION_SEND_PER_DAY_LIMIT);
        }
        Integer receiverType = requestParam.getReceiverType();
        List<Long> receiverIdList = new ArrayList<>();
        if (receiverType == 1) {
            receiverIdList = userTenantService.getTenantAllMembers(tenantId).stream().map(UserTenantDO::getUserId).toList();
        } else if (receiverType == 2) {
            receiverIdList = userTenantService.getTenantMembers(tenantId).stream().map(UserTenantDO::getUserId).toList();
        } else if (receiverType == 3) {
            receiverIdList = userTenantService.getTenantAdmins(tenantId).stream().map(UserTenantDO::getUserId).toList();
        }

        try {
            notificationService.createAndPush(tenantId, "ANNOUNCEMENT", requestParam.getSeverity(),
                    requestParam.getTitle(), requestParam.getContent(), userId, receiverIdList);
        } catch (Exception e) {
            log.error("Sending notification in tenant error: tenant {}", tenantId);
            throw new ServerException(TenantErrorCodeEnum.TENANT_NOTIFICATION_PUSH_ERROR);
        }
        return Boolean.TRUE;
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
        int tenantInserted = tenantMapper.insert(tenantDO);
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

    private Date getStartOfToday() {
        return Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
