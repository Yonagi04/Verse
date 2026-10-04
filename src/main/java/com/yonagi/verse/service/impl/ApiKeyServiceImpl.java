package com.yonagi.verse.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.cache.QueryCatalogue.Access;
import com.yonagi.verse.service.cache.QueryCacheBehaviors;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.ApiKeyErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dto.req.ApiKeyCreateReqDTO;
import com.yonagi.verse.dto.req.ApiKeyUpdateReqDTO;
import com.yonagi.verse.dto.resp.ApiKeyListRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyPageRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyRespDTO;
import com.yonagi.verse.service.ApiKeyService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

/**
 * API Key 管理服务实现
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKeyDO> implements ApiKeyService {

    private static final String API_KEY_PREFIX = "sk_";
    private static final int API_KEY_PREFIX_LENGTH = 10;
    private static final String CREATE_API_KEY_MESSAGE = "请将此 API key 保存在安全且易于访问的地方。出于安全原因，你将无法通过 API keys 管理界面再次查看它。如果你丟失了这个 key，将需要重新创建。";
    private static final String CREATE_API_KEY_TIP = "提示：不要与他人共享你的 API key，或将其暴露在浏览器或其他客户端代码中。";

    private final com.yonagi.verse.service.budget.CostBudgetService costBudgetService;
    private final TenantMapper tenantMapper;
    private final UserTenantService userTenantService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiKeyRespDTO createApiKey(Long userId, Long tenantId, ApiKeyCreateReqDTO requestParam) {
        validateTenantAndMembership(userId, tenantId);

        Date expiresAt = requestParam.getExpiresAt();
        if (expiresAt != null && expiresAt.before(new Date())) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_EXPIRE_DATE_IS_INVALID);
        }

        String apiKey = generateApiKey();
        ApiKeyDO apiKeyDO = new ApiKeyDO();
        apiKeyDO.setApiKeyId(SnowflakeIdUtil.nextId());
        apiKeyDO.setUserId(userId);
        apiKeyDO.setTenantId(tenantId);
        // 仅存储 SHA-256 哈希，明文只在本方法内返回一次
        apiKeyDO.setApiKey(DigestUtil.sha256Hex(apiKey));
        apiKeyDO.setKeyPrefix(apiKey.substring(0, API_KEY_PREFIX_LENGTH));
        apiKeyDO.setName(requestParam.getName());
        apiKeyDO.setStatus(1);
        apiKeyDO.setExpiresAt(expiresAt);
        apiKeyDO.setRateLimitRpm(normalizeLimit(requestParam.getRpm()));
        apiKeyDO.setRateLimitTpm(normalizeLimit(requestParam.getTpm()));
        apiKeyDO.setCreateTime(new Date());
        apiKeyDO.setCostLimitEnabled(false);
        apiKeyDO.setCostConfigVersion(0L);
        apiKeyDO.setCostDataState("READY");
        costBudgetService.merge(apiKeyDO, requestParam.getCostLimit());
        int inserted = baseMapper.insert(apiKeyDO);
        if (inserted < 1) {
            log.error("Create API key failed, userId: {}, tenantId: {}", userId, tenantId);
            throw new ServerException(ApiKeyErrorCodeEnum.API_KEY_CREATE_FAILED);
        }

        ApiKeyRespDTO respDTO = new ApiKeyRespDTO();
        respDTO.setApiKeyId(apiKeyDO.getApiKeyId());
        respDTO.setName(apiKeyDO.getName());
        respDTO.setExpiresAt(apiKeyDO.getExpiresAt());
        respDTO.setApiKey(apiKey);
        respDTO.setCostLimit(com.yonagi.verse.dto.resp.CostLimitConfig.from(apiKeyDO));
        respDTO.setCreateKeyMessage(CREATE_API_KEY_MESSAGE);
        respDTO.setCreateKeyTip(CREATE_API_KEY_TIP);
        return respDTO;
    }

    @Override
    @QueryCached(keyPrefix = API_KEY_LIST_KEY, seconds = HOURS_4, access = Access.TENANT,
            tables = {"t_tenant", "t_user_tenant", "t_api_key"}, behavior = QueryCacheBehaviors.ApiKeys.class)
    public ApiKeyPageRespDTO listApiKeys(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        return withCurrentListState(userId, tenantId, listApiKeyMetadata(userId, tenantId, pageNum, pageSize));
    }

    /** 缓存稳定的分页配置，不含 Key 哈希和高频最近使用时间，也不在读取中回写过期状态。 */
    public ApiKeyPageRespDTO listApiKeyMetadata(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        validateTenantAndMembership(userId, tenantId);
        if (pageNum == null) {
            pageNum = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        Page<ApiKeyDO> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.lambdaQuery(ApiKeyDO.class)
                .select(ApiKeyDO::getApiKeyId, ApiKeyDO::getName, ApiKeyDO::getKeyPrefix, ApiKeyDO::getStatus,
                        ApiKeyDO::getExpiresAt, ApiKeyDO::getRateLimitRpm, ApiKeyDO::getRateLimitTpm, ApiKeyDO::getCreateTime,
                        ApiKeyDO::getCostLimitEnabled, ApiKeyDO::getCostLimitDailyFen, ApiKeyDO::getCostLimitWeeklyFen,
                        ApiKeyDO::getCostLimitMonthlyFen, ApiKeyDO::getCostConfigVersion)
                .eq(ApiKeyDO::getUserId, userId)
                .eq(ApiKeyDO::getTenantId, tenantId)
                .in(ApiKeyDO::getStatus, 1, 2)
                .orderByDesc(ApiKeyDO::getCreateTime));
        List<ApiKeyDO> apiKeyList = page.getRecords();
        List<ApiKeyListRespDTO> records = apiKeyList.stream().map(this::toListRespDTO).toList();
        return new ApiKeyPageRespDTO(records, page.getTotal(), page.getPages(), pageNum, pageSize);
    }

    /** 元数据命中后仍显示最新使用时间与自然过期状态；复制响应，避免污染缓存内容。 */
    public ApiKeyPageRespDTO withCurrentListState(Long userId, Long tenantId, ApiKeyPageRespDTO metadata) {
        List<Long> ids = metadata.getRecords().stream().map(ApiKeyListRespDTO::getApiKeyId).toList();
        java.util.Map<Long, Date> lastUsed = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            baseMapper.selectList(Wrappers.lambdaQuery(ApiKeyDO.class)
                    .select(ApiKeyDO::getApiKeyId, ApiKeyDO::getLastUsedAt)
                    .eq(ApiKeyDO::getUserId, userId).eq(ApiKeyDO::getTenantId, tenantId)
                    .in(ApiKeyDO::getApiKeyId, ids)).forEach(key -> lastUsed.put(key.getApiKeyId(), key.getLastUsedAt()));
        }
        Date now = new Date();
        List<ApiKeyListRespDTO> records = metadata.getRecords().stream().map(record -> {
            ApiKeyListRespDTO current = new ApiKeyListRespDTO();
            org.springframework.beans.BeanUtils.copyProperties(record, current);
            current.setLastUsedAt(lastUsed.get(record.getApiKeyId()));
            if (Integer.valueOf(1).equals(current.getStatus())
                    && current.getExpiresAt() != null && !current.getExpiresAt().after(now)) current.setStatus(2);
            return current;
        }).toList();
        return new ApiKeyPageRespDTO(records, metadata.getTotal(), metadata.getTotalPages(), metadata.getPage(), metadata.getPageSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean revokeApiKey(Long userId, Long tenantId, Long apiKeyId) {
        validateTenantAndMembership(userId, tenantId);
        int updated = baseMapper.update(Wrappers.lambdaUpdate(ApiKeyDO.class)
                .eq(ApiKeyDO::getApiKeyId, apiKeyId)
                .eq(ApiKeyDO::getUserId, userId)
                .eq(ApiKeyDO::getTenantId, tenantId)
                .in(ApiKeyDO::getStatus, 1, 2)
                .set(ApiKeyDO::getStatus, 0));
        if (updated < 1) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_NOT_EXIST);
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateApiKey(Long userId, Long tenantId, Long apiKeyId, ApiKeyUpdateReqDTO requestParam) {
        validateTenantAndMembership(userId, tenantId);
        ApiKeyDO apiKey = costBudgetService.lockOwnedKey(tenantId, apiKeyId, userId);
        if (!userId.equals(apiKey.getUserId())) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_NOT_EXIST);
        } else if (apiKey.getStatus() == 0) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_CAN_NOT_UPDATE);
        }
        Date oldExpiresAt = apiKey.getExpiresAt();
        Date newExpiresAt = requestParam.getExpiresAt();
        Date now = new Date();
        if (newExpiresAt != null && oldExpiresAt != null && newExpiresAt.before(oldExpiresAt)) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_EXPIRE_DATE_BEFORE_OLD_DATE);
        } else if (newExpiresAt != null && !newExpiresAt.equals(oldExpiresAt) && newExpiresAt.before(now)) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_EXPIRE_DATE_IS_INVALID);
        }

        costBudgetService.merge(apiKey, requestParam.getCostLimit());
        int updated = baseMapper.update(Wrappers.lambdaUpdate(ApiKeyDO.class)
                .eq(ApiKeyDO::getApiKeyId, apiKeyId)
                .eq(ApiKeyDO::getTenantId, tenantId)
                .eq(ApiKeyDO::getUserId, userId)
                .set(ApiKeyDO::getCostLimitEnabled, apiKey.getCostLimitEnabled())
                .set(ApiKeyDO::getCostLimitDailyFen, apiKey.getCostLimitDailyFen())
                .set(ApiKeyDO::getCostLimitWeeklyFen, apiKey.getCostLimitWeeklyFen())
                .set(ApiKeyDO::getCostLimitMonthlyFen, apiKey.getCostLimitMonthlyFen())
                .set(ApiKeyDO::getCostConfigVersion, apiKey.getCostConfigVersion())
                .set(ApiKeyDO::getName, requestParam.getName())
                .set(ApiKeyDO::getExpiresAt, newExpiresAt)
                .set(ApiKeyDO::getRateLimitRpm, normalizeLimit(requestParam.getRpm()))
                .set(ApiKeyDO::getRateLimitTpm, normalizeLimit(requestParam.getTpm()))
                .set(ApiKeyDO::getStatus, newExpiresAt != null && newExpiresAt.before(now) ? 2 : 1));
        if (updated < 1) {
            log.error("Update API key failed, userId: {}, tenantId: {}, apiKeyId: {}", userId, tenantId, apiKeyId);
            throw new ServerException(ApiKeyErrorCodeEnum.API_KEY_UPDATE_ERROR);
        }
        // 失效认证缓存，使新的限流配置对后续请求立即生效
        stringRedisTemplate.delete(RedisKeyConstant.API_KEY_AUTH_KEY + apiKey.getApiKey());
        return Boolean.TRUE;
    }

    @Override
    public com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO costStatus(Long userId, Long tenantId, Long keyId) {
        validateTenantAndMembership(userId, tenantId);
        return costBudgetService.status(tenantId, keyId, userId);
    }

    private void validateTenantAndMembership(Long userId, Long tenantId) {
        TenantDO tenantDO = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenantDO == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        if (!userTenantService.isUserJoinedTenant(userId, tenantId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
    }

    private ApiKeyListRespDTO toListRespDTO(ApiKeyDO apiKeyDO) {
        ApiKeyListRespDTO dto = new ApiKeyListRespDTO();
        dto.setApiKeyId(apiKeyDO.getApiKeyId());
        dto.setName(apiKeyDO.getName());
        dto.setKeyPrefix(apiKeyDO.getKeyPrefix());
        dto.setStatus(apiKeyDO.getStatus());
        dto.setExpiresAt(apiKeyDO.getExpiresAt());
        dto.setRateLimitRpm(apiKeyDO.getRateLimitRpm());
        dto.setRateLimitTpm(apiKeyDO.getRateLimitTpm());
        dto.setCreateTime(apiKeyDO.getCreateTime());
        dto.setCostLimit(com.yonagi.verse.dto.resp.CostLimitConfig.from(apiKeyDO));
        return dto;
    }

    /**
     * 规范化限流值：{@code null} 或非正数统一存为 {@code null}（不限），仅保留正数上限。
     */
    private Integer normalizeLimit(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private String generateApiKey() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : randomBytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return API_KEY_PREFIX + sb;
    }
}
