package com.yonagi.verse.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import org.checkerframework.checker.units.qual.C;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;

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

    private final TenantMapper tenantMapper;
    private final UserTenantService userTenantService;

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
        apiKeyDO.setCreateTime(new Date());
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
        respDTO.setKeyPrefix(apiKeyDO.getKeyPrefix());
        respDTO.setCreatedAt(apiKeyDO.getCreateTime());
        return respDTO;
    }

    @Override
    public ApiKeyPageRespDTO listApiKeys(Long userId, Long tenantId, Integer pageNum, Integer pageSize) {
        validateTenantAndMembership(userId, tenantId);
        if (pageNum == null) {
            pageNum = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        Page<ApiKeyDO> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.lambdaQuery(ApiKeyDO.class)
                .eq(ApiKeyDO::getUserId, userId)
                .eq(ApiKeyDO::getTenantId, tenantId)
                .in(ApiKeyDO::getStatus, 1, 2)
                .orderByDesc(ApiKeyDO::getCreateTime));
        Date now = new Date();
        List<ApiKeyDO> apiKeyList = page.getRecords();
        for (ApiKeyDO apiKeyDO : apiKeyList) {
            // 仅当仍为正常状态且已过期时，标记为过期状态（status=2）
            if (Integer.valueOf(1).equals(apiKeyDO.getStatus())
                    && apiKeyDO.getExpiresAt() != null && apiKeyDO.getExpiresAt().before(now)) {
                baseMapper.update(Wrappers.lambdaUpdate(ApiKeyDO.class)
                        .eq(ApiKeyDO::getApiKeyId, apiKeyDO.getApiKeyId())
                        .set(ApiKeyDO::getStatus, 2));
                apiKeyDO.setStatus(2);
            }
        }
        List<ApiKeyListRespDTO> records = apiKeyList.stream().map(this::toListRespDTO).toList();
        return new ApiKeyPageRespDTO(records, page.getTotal(), page.getPages(), pageNum, pageSize);
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
    public Boolean updateApiKey(Long userId, Long tenantId, Long apiKeyId, ApiKeyUpdateReqDTO requestParam) {
        validateTenantAndMembership(userId, tenantId);
        ApiKeyDO apiKey = baseMapper.selectOne(Wrappers.lambdaQuery(ApiKeyDO.class)
                .eq(ApiKeyDO::getApiKeyId, apiKeyId));
        if (apiKey == null) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_NOT_EXIST);
        } else if (apiKey.getStatus() == 0) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_CAN_NOT_UPDATE);
        }
        Date oldExpiresAt = apiKey.getExpiresAt();
        Date newExpiresAt = requestParam.getExpiresAt();
        Date now = new Date();
        if (newExpiresAt != null && oldExpiresAt != null && newExpiresAt.before(oldExpiresAt)) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_EXPIRE_DATE_BEFORE_OLD_DATE);
        } else if (newExpiresAt != null && newExpiresAt.before(now)) {
            throw new ClientException(ApiKeyErrorCodeEnum.API_KEY_EXPIRE_DATE_IS_INVALID);
        }

        int updated = baseMapper.update(Wrappers.lambdaUpdate(ApiKeyDO.class)
                .eq(ApiKeyDO::getApiKeyId, apiKeyId)
                .set(ApiKeyDO::getName, requestParam.getName())
                .set(ApiKeyDO::getExpiresAt, newExpiresAt)
                .set(ApiKeyDO::getStatus, 1));
        if (updated < 1) {
            log.error("Update API key failed, userId: {}, tenantId: {}, apiKeyId: {}", userId, tenantId, apiKeyId);
            throw new ServerException(ApiKeyErrorCodeEnum.API_KEY_UPDATE_ERROR);
        }
        return Boolean.TRUE;
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
        dto.setLastUsedAt(apiKeyDO.getLastUsedAt());
        dto.setExpiresAt(apiKeyDO.getExpiresAt());
        dto.setCreateTime(apiKeyDO.getCreateTime());
        return dto;
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
