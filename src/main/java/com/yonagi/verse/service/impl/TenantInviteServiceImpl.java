package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.cache.QueryCatalogue.Access;
import com.yonagi.verse.service.cache.QueryCacheBehaviors;
import com.alibaba.fastjson2.JSON;
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
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
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
import java.util.concurrent.TimeUnit;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantInviteServiceImpl implements TenantInviteService {

    private static final Integer INVITE_CODE_LENGTH = 8;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

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
    @QueryCached(keyPrefix = TENANT_INVITE_CODE_KEY, seconds = HOURS_1, access = Access.INVITE,
            tables = {"t_tenant", "t_tenant_invite"})
    public TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode) {
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getCode, inviteCode));
        if (inviteDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }
        Long tenantId = inviteDO.getTenantId();
        TenantDO tenantDO = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId).eq(TenantDO::getStatus, 1).eq(TenantDO::getDelFlag, 0));
        if (tenantDO == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        String name = tenantDO.getName();
        return new TenantJoinInfoRespDTO(name, inviteCode);
    }

    @Override
    @QueryCached(keyPrefix = TENANT_INVITE_LIST_KEY, seconds = MINUTES_30, access = Access.TEAM,
            tables = {"t_tenant", "t_user_tenant", "t_tenant_invite"}, behavior = QueryCacheBehaviors.Invites.class)
    public TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        return pageAvailableInvites(inviteCandidates(userId, tenantId), pageNum, pageSize);
    }

    /** 全部候选作为缓存内容；分页结果不能因某条邀请码自然过期而冻结。 */
    public TenantInviteListRespDTO inviteCandidates(Long userId, Long tenantId) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        java.util.List<TenantInviteListRespDTO.TenantInviteInfo> records = tenantInviteMapper.selectAvailableCandidates(tenantId, new Date());
        if (!records.isEmpty()) {
            records.forEach(record -> {
                String inviteUrl = frontendBaseUrl + "/join/" + record.getCode();
                record.setInviteUrl(inviteUrl);
            });
        }
        TenantInviteListRespDTO resp = new TenantInviteListRespDTO();
        resp.setInviteCodes(records);
        return resp;
    }

    /** 每次读取都过滤自然过期项并重新分页，保持总数及页内容一致。 */
    public TenantInviteListRespDTO pageAvailableInvites(TenantInviteListRespDTO candidates, Integer pageNum, Integer pageSize) {
        if (pageNum == null || pageSize == null || pageNum < 1 || pageSize < 1)
            throw new ClientException("分页参数必须大于 0");
        Date now = new Date();
        java.util.List<TenantInviteListRespDTO.TenantInviteInfo> available = candidates.getInviteCodes().stream()
                .filter(record -> record.getExpiresAt() == null || record.getExpiresAt().after(now)).toList();
        int from = (int) Math.min(available.size(), ((long) pageNum - 1) * pageSize);
        int to = (int) Math.min(available.size(), (long) from + pageSize);
        return new TenantInviteListRespDTO().setInviteCodes(available.subList(from, to))
                .setTotal((long) available.size()).setTotalPages((available.size() + (long) pageSize - 1) / pageSize)
                .setPage(pageNum).setPageSize(pageSize);
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
        // 加入操作实时校验有效性，不能复用失效前的邀请码状态。
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getCode, inviteCode));
        if (inviteDO == null || inviteDO.getIsActive() == 0) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }
        if (inviteDO.getExpiresAt() != null && inviteDO.getExpiresAt().before(new Date())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }
        return inviteDO;
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
