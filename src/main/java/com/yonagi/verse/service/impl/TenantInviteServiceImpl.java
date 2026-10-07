package com.yonagi.verse.service.impl;

import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import com.yonagi.verse.service.tenant.TenantInviteAccessPolicy;
import com.yonagi.verse.common.validation.PaginationPolicy;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.yonagi.verse.common.cache.QueryCached;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.enums.TenantActivityTargetType;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.async.event.CounterEvent;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dao.mapper.TenantInviteMapper;
import com.yonagi.verse.dto.req.TenantInviteReqDTO;
import com.yonagi.verse.dto.resp.TenantInviteListRespDTO;
import com.yonagi.verse.dto.resp.TenantInviteRespDTO;
import com.yonagi.verse.dto.resp.TenantJoinInfoRespDTO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.service.TenantInviteService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
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
import java.util.Date;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantInviteServiceImpl implements TenantInviteService {

    private static final Integer INVITE_CODE_LENGTH = 8;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantAccessPolicy tenantAccess;
    private final TenantInviteAccessPolicy inviteAccess;
    private final TenantInviteMapper tenantInviteMapper;
    private final TenantMapper tenantMapper;
    private final TenantValidationHelper validationHelper;
    private final UserTenantService userTenantService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RBloomFilter<String> inviteCodeFilter;
    private final DomainEventPublisher domainEventPublisher;
    private final TenantActivityRecorder activityRecorder;

    @Value("${verse.tenant.max-invite-code-per-day:10}")
    private Integer maxInviteCodePerDay;

    @Value("${verse.frontend-baseurl}")
    private String frontendBaseUrl;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantInviteRespDTO inviteUser(Long userId, Long tenantId, TenantInviteReqDTO requestParam) {
        LambdaQueryWrapper<TenantInviteDO> queryWrapper = Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getTenantId, tenantId)
                .ge(TenantInviteDO::getCreateTime, getStartOfToday());
        Long count = tenantInviteMapper.selectCount(queryWrapper);
        if (count >= maxInviteCodePerDay) {
            throw new ClientException(TenantErrorCodeEnum.INVITE_CODE_GENE_PER_DAY_LIMIT);
        }

        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.INVITE_CODE_CAN_NOT_GENE);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        String inviteCode = generateInviteCode();
        if (inviteCodeFilter.contains(inviteCode)) {
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
        inviteCodeFilter.add(inviteCode);

        TenantInviteRespDTO resp = new TenantInviteRespDTO();
        resp.setInviteCode(inviteCode);
        resp.setInviteUrl(frontendBaseUrl + "/join/" + inviteCode);
        resp.setExpiresAt(requestParam.getExpireAt());
        return resp;
    }

    @Override
    @QueryCached(keyPrefix = TENANT_INVITE_CODE_KEY, seconds = HOURS_1, access = TenantInviteAccessPolicy.class,
            tables = {"t_tenant", "t_tenant_invite"})
    public TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode) {
        TenantInviteDO invite = inviteAccess.requireValidCode(inviteCode);
        TenantDO tenant = tenantAccess.activeTenant(invite.getTenantId());
        return new TenantJoinInfoRespDTO(tenant.getName(), inviteCode);
    }

    @Override
    public TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        PaginationPolicy.validate(pageNum, pageSize);
        tenantAccess.requireTeamMember(userId, tenantId);
        // 自然过期没有写入失效事件；实时 SQL 分页避免缓存整租户候选或冻结页内容。
        Page<TenantInviteListRespDTO.TenantInviteInfo> requested = new Page<>(pageNum, pageSize);
        requested.setOptimizeJoinOfCountSql(false);
        Page<TenantInviteListRespDTO.TenantInviteInfo> page = tenantInviteMapper.selectPageByTenantId(
                requested, tenantId, new Date());
        page.getRecords().forEach(record -> record.setInviteUrl(frontendBaseUrl + "/join/" + record.getCode()));
        return new TenantInviteListRespDTO().setInviteCodes(page.getRecords())
                .setTotal(page.getTotal()).setTotalPages(page.getPages()).setPage(pageNum).setPageSize(pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deactivateInviteCode(Long userId, Long tenantId, Long inviteCodeId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

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

        LambdaUpdateWrapper<TenantInviteDO> updateWrapper = Wrappers.lambdaUpdate(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, inviteCodeId)
                .eq(TenantInviteDO::getTenantId, tenantId)
                .set(TenantInviteDO::getIsActive, 0);
        int update = tenantInviteMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Deactivate Invite Code Error: tenant {}, inviteCodeId {}", tenantId, inviteCodeId);
            throw new ServerException(TenantErrorCodeEnum.INVITE_CODE_DEACTIVATE_ERROR);
        }
        record(tenantId, userId, TenantActivityType.INVITE_DISABLED, inviteDO, "DISABLED");
        String cacheKey = RedisKeyConstant.TENANT_INVITE_CODE_KEY + inviteDO.getCode();
        stringRedisTemplate.delete(cacheKey);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean activateInviteCode(Long userId, Long tenantId, Long inviteCodeId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

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

        LambdaUpdateWrapper<TenantInviteDO> updateWrapper = Wrappers.lambdaUpdate(TenantInviteDO.class)
                .eq(TenantInviteDO::getId, inviteCodeId)
                .eq(TenantInviteDO::getTenantId, tenantId)
                .set(TenantInviteDO::getIsActive, 1);
        int update = tenantInviteMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Activate Invite Code Error: tenant {}, inviteCodeId {}", tenantId, inviteCodeId);
            throw new ServerException(TenantErrorCodeEnum.INVITE_CODE_ACTIVATE_ERROR);
        }
        record(tenantId, userId, TenantActivityType.INVITE_ENABLED, inviteDO, "ENABLED");
        return Boolean.TRUE;
    }

    @Override
    public TenantInviteDO validateAndGetInviteCode(String inviteCode) {
        return inviteAccess.requireValidCode(inviteCode);
    }

    @Override
    public void incrementUsageCount(Long inviteId) {
        CounterEvent event = new CounterEvent();
        event.setInviteId(inviteId);
        event.setKey(String.valueOf(inviteId));
        domainEventPublisher.publishInTx(event);
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

    private void record(Long tenantId, Long userId, TenantActivityType type, TenantInviteDO invite, String status) {
        activityRecorder.record(tenantId, TenantActivityDraft.of(type).actor(userId)
                .target(TenantActivityTargetType.INVITE, invite.getId(), "邀请")
                .detail("status", status).detail("expiresAt", invite.getExpiresAt()));
    }
}
