package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
    public TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode) {
        TenantInviteDO inviteDO = tenantInviteMapper.selectOne(Wrappers.lambdaQuery(TenantInviteDO.class)
                .eq(TenantInviteDO::getCode, inviteCode));
        if (inviteDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_INVITE_CODE_EXPIRED);
        }
        Long tenantId = inviteDO.getTenantId();
        String name;

        String cacheKey = RedisKeyConstant.TENANT_INFO_KEY + tenantId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            TenantInfoRespDTO respDTO = JSON.parseObject(cachedJson, TenantInfoRespDTO.class);
            name = respDTO.getName();
        } else {
            TenantDO tenantDO = tenantMapper.selectOne(
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
    public TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        validationHelper.validateTenantTeamActive(tenantId, TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        Boolean isJoinedTenant = userTenantService.isUserJoinedTenant(userId, tenantId);
        if (!isJoinedTenant) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        Date now = new Date();
        Page<TenantInviteListRespDTO.TenantInviteInfo> page = tenantInviteMapper.selectPageByTenantId(new Page<>(pageNum, pageSize), tenantId, now);
        java.util.List<TenantInviteListRespDTO.TenantInviteInfo> records = page.getRecords();
        if (!records.isEmpty()) {
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
        inviteDO.setIsActive(1);
        String cacheKey = RedisKeyConstant.TENANT_INVITE_CODE_KEY + inviteDO.getCode();
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(inviteDO), 15, TimeUnit.MINUTES);
        return Boolean.TRUE;
    }

    @Override
    public TenantInviteDO validateAndGetInviteCode(String inviteCode) {
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
        return inviteDO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void incrementUsageCount(Long inviteId) {
        TenantInviteDO inviteDO = tenantInviteMapper.selectById(inviteId);
        if (inviteDO != null) {
            inviteDO.setUsageCount(inviteDO.getUsageCount() + 1);
            if (tenantInviteMapper.updateById(inviteDO) < 1) {
                log.error("Update Invite Code Usage Count Error: inviteId {}", inviteId);
            }
        }
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
}
