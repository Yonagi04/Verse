package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.enums.TenantActivityTargetType;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LLMProviderEnum;
import com.yonagi.verse.common.enums.LlmManageErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.common.util.SensitiveUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.LlmServiceAddReqDTO;
import com.yonagi.verse.dto.req.LlmServiceRemoveReqDTO;
import com.yonagi.verse.dto.req.LlmServiceUpdateReqDTO;
import com.yonagi.verse.dto.resp.LlmServiceRemovePreRespDTO;
import com.yonagi.verse.dto.resp.LlmServiceInfoRespDTO;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO;
import com.yonagi.verse.service.LlmManageService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.pricing.LlmMetadataService;
import com.yonagi.verse.service.pricing.PricingConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 11:07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmManageServiceImpl extends ServiceImpl<LlmServiceMapper, LlmServiceDO> implements LlmManageService {

    private static final String LLM_SERVICE_PREPARE_REMOVE_INFO = "此LLM将会被删除。删除后租户成员将无法再访问此模型，依赖此LLM的其他服务也会受到影响。" +
            "删除模型后将无法恢复。由于模型实例与模型服务ID唯一绑定，因此在删除模型后即使重新添加完全相同的模型也有可能会对存量业务带来影响。" +
            "建议在删除模型前观测模型的使用情况，并利用租户内公告进行周知租户成员，提前完成资源迁移。如有疑问，请联系技术支持。";

    private final TenantMapper tenantMapper;
    private final UserTenantService userTenantService;
    private final AesUtil aesUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedissonClient redissonClient;
    private final LlmMetadataService metadataService;
    private final PricingConfigurationService pricingConfigurationService;
    private final TenantActivityRecorder activityRecorder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addLlmService(Long userId, Long tenantId, LlmServiceAddReqDTO requestParam) {
        validateTenantAndMembership(userId, tenantId);

        Long exists = baseMapper.selectCount(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getName, requestParam.getName())
                .eq(LlmServiceDO::getDelFlag, 0));
        if (exists != null && exists != 0) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_NAME_DUPLICATED);
        }

        RLock lock = redissonClient.getLock(RedisKeyConstant.LLM_LOCK_KEY + tenantId + ":" + requestParam.getName());

        try {
            if (lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                try {
                    String encryptApiKey = aesUtil.encrypt(requestParam.getApiKey());
                    Long llmServiceId = SnowflakeIdUtil.nextId();
                    LlmServiceDO llmServiceDO = LlmServiceDO.builder()
                            .serviceId(llmServiceId)
                            .tenantId(tenantId)
                            .name(requestParam.getName())
                            .provider(requestParam.getProvider())
                            .apiUrl(requestParam.getApiUrl())
                            .apiKey(encryptApiKey)
                            .modelName(requestParam.getModelName())
                            .description(normalizeDescription(requestParam.getDescription()))
                            .status(1)
                            .createdBy(userId)
                            .rateLimitRpm(requestParam.getRpm())
                            .rateLimitTpm(requestParam.getTpm())
                            .contextWindow(requestParam.getContextWindow())
                            .maxOutputTokens(requestParam.getMaxOutputTokens())
                            .build();
                    validateTokenLimits(null, llmServiceDO.getContextWindow(), llmServiceDO.getMaxOutputTokens(), true);
                    int insert = baseMapper.insert(llmServiceDO);
                    if (insert < 1) {
                        log.error("insert LLM service error: userId {}, tenantId {}", userId, tenantId);
                        throw new ServerException(LlmManageErrorCodeEnum.LLM_ADD_FAILED);
                    }
                    metadataService.replaceTags(llmServiceId, requestParam.getTagCodes());
                    if (requestParam.getPricing() != null) {
                        pricingConfigurationService.replace(userId, llmServiceDO, requestParam.getPricing());
                        baseMapper.update(Wrappers.lambdaUpdate(LlmServiceDO.class)
                                .eq(LlmServiceDO::getServiceId, llmServiceId)
                                .set(LlmServiceDO::getActivePricingId, llmServiceDO.getActivePricingId()));
                    }
                    record(tenantId, TenantActivityDraft.of(TenantActivityType.LLM_SERVICE_CREATED).actor(userId)
                            .target(TenantActivityTargetType.LLM_SERVICE, llmServiceId, llmServiceDO.getName())
                            .detail("provider", llmServiceDO.getProvider()).detail("modelName", llmServiceDO.getModelName()));

                    // 缓存
                    stringRedisTemplate.opsForValue().set(RedisKeyConstant.LLM_SERVICE_INFO_KEY + llmServiceId,
                            JSON.toJSONString(llmServiceDO),
                            30,
                            TimeUnit.MINUTES);
                    stringRedisTemplate.opsForHash().put(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId,
                            requestParam.getName(),
                            String.valueOf(llmServiceId));
                    stringRedisTemplate.expire(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, 3, TimeUnit.HOURS);
                    stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_LIST_KEY + tenantId);
                    return Boolean.TRUE;
                } finally {
                    lock.unlock();
                }
            } else {
                throw new ClientException(LlmManageErrorCodeEnum.LLM_NAME_DUPLICATED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("llm name lock interrupt for name {}", requestParam.getName(), e);
            throw new ServerException(LlmManageErrorCodeEnum.THREAD_INTERRUPTED);
        } catch (Exception e) {
            if (e instanceof AbstractException) {
                throw e;
            }
            log.error("add LLM service error: userId {}, tenantId {}", userId, tenantId);
            throw new ServerException(LlmManageErrorCodeEnum.LLM_ADD_FAILED);
        }
    }

    @Override
    public LlmServiceListRespDTO listLlmService(Long userId, Long tenantId, Integer pageNum,
                                                Integer pageSize, String keyword, String tagCodes) {
        validateTenantAndMembership(userId, tenantId);
        List<LlmServiceListRespDTO.LlmServiceInfo> all = loadServiceInfos(tenantId);
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim();
            all = all.stream()
                    .filter(info -> matchesKeyword(info, kw))
                    .toList();
        }
        Map<Long, List<String>> tags = metadataService.tagsByServiceIds(all.stream()
                .map(LlmServiceListRespDTO.LlmServiceInfo::getServiceId)
                .toList());
        all.forEach(info -> info.setTagCodes(tags.getOrDefault(info.getServiceId(), List.of())));
        if (StrUtil.isNotBlank(tagCodes)) {
            List<String> filters = Arrays.stream(tagCodes.split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .toList();
            metadataService.validateCodes(filters);
            all = all.stream().filter(info -> info.getTagCodes().stream().anyMatch(filters::contains)).toList();
        }
        return paginate(all, pageNum, pageSize);
    }

    /**
     * 关键词模糊匹配：服务别名、供应商英文标识、供应商中文显示名及别名（均忽略大小写）。
     */
    private boolean matchesKeyword(LlmServiceListRespDTO.LlmServiceInfo info, String keyword) {
        if (StrUtil.containsIgnoreCase(info.getName(), keyword)
                || StrUtil.containsIgnoreCase(info.getProvider(), keyword)) {
            return true;
        }
        LLMProviderEnum providerEnum = LLMProviderEnum.fromProvider(info.getProvider());
        if (providerEnum == null) {
            return false;
        }
        if (StrUtil.containsIgnoreCase(providerEnum.getDisplayName(), keyword)) {
            return true;
        }
        String[] aliases = providerEnum.getAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                if (StrUtil.containsIgnoreCase(alias, keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<LlmServiceListRespDTO.LlmServiceInfo> loadServiceInfos(Long tenantId) {
        String key = RedisKeyConstant.LLM_SERVICE_LIST_KEY + tenantId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null) {
            return JSON.parseArray(json, LlmServiceListRespDTO.LlmServiceInfo.class);
        }
        List<LlmServiceListRespDTO.LlmServiceInfo> list = baseMapper.selectByTenantId(tenantId);
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(list), 30, TimeUnit.MINUTES);
        return list;
    }

    private LlmServiceListRespDTO paginate(List<LlmServiceListRespDTO.LlmServiceInfo> all,
                                           int pageNum, int pageSize) {
        int total = all.size();
        long totalPages = (total + pageSize - 1L) / pageSize;
        int from = Math.max(0, Math.min((pageNum - 1) * pageSize, total));
        int to = Math.min(from + pageSize, total);
        return new LlmServiceListRespDTO()
                .setServiceInfoList(all.subList(from, to))
                .setTotal((long) total)
                .setTotalPages(totalPages)
                .setPage(pageNum)
                .setPageSize(pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateLlmService(Long userId, Long tenantId, Long serviceId, LlmServiceUpdateReqDTO requestParam) {
        validateTenantAndMembership(userId, tenantId);

        // 部分更新：至少需要一个字段非空
        if (StrUtil.isBlank(requestParam.getName())
                && StrUtil.isBlank(requestParam.getApiUrl())
                && StrUtil.isBlank(requestParam.getApiKey())
                && StrUtil.isBlank(requestParam.getModelName())
                && requestParam.getDescription() == null
                && requestParam.getRpm() == null
                && requestParam.getTpm() == null
                && requestParam.getFallbackServiceId() == null
                && requestParam.getTagCodes() == null
                && requestParam.getContextWindow() == null
                && requestParam.getMaxOutputTokens() == null
                && requestParam.getPricing() == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_UPDATE_PARAM_EMPTY);
        }
        // 查询对应的服务是否存在 or 是否启用
        LlmServiceDO llmServiceDO = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (llmServiceDO == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
        } else if (llmServiceDO.getStatus() == 0) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_CAN_NOT_UPDATE);
        }
        validateTokenLimits(llmServiceDO, requestParam.getContextWindow(), requestParam.getMaxOutputTokens(), false);
        List<String> oldTagCodes = requestParam.getTagCodes() == null
                ? null
                : metadataService.tags(serviceId);
        List<String> changedFields = llmChangedFields(llmServiceDO, requestParam, oldTagCodes);

        String newName = StrUtil.isBlank(requestParam.getName()) ? null : requestParam.getName().trim();
        boolean nameChanged = newName != null && !newName.equals(llmServiceDO.getName());

        if (hasServiceFieldUpdate(requestParam)) {
            if (nameChanged) {
                checkNameUniqueAndUpdate(tenantId, serviceId, newName, requestParam);
            } else {
                doUpdateService(tenantId, serviceId, requestParam);
            }
        }
        if (requestParam.getTagCodes() != null) {
            metadataService.replaceTags(serviceId, requestParam.getTagCodes());
        }
        if (requestParam.getPricing() != null) {
            pricingConfigurationService.replace(userId, llmServiceDO, requestParam.getPricing());
            baseMapper.update(Wrappers.lambdaUpdate(LlmServiceDO.class)
                    .eq(LlmServiceDO::getServiceId, serviceId)
                    .eq(LlmServiceDO::getTenantId, tenantId)
                    .set(LlmServiceDO::getActivePricingId, llmServiceDO.getActivePricingId()));
        }

        // 失效缓存；名称变更时重建路由索引
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_LIST_KEY + tenantId);
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_INFO_KEY + serviceId);
        stringRedisTemplate.opsForHash().delete(
                RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, llmServiceDO.getName()
        );
        if (nameChanged) {
            stringRedisTemplate.opsForHash().put(
                    RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, newName, String.valueOf(serviceId)
            );
            stringRedisTemplate.expire(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, 3, TimeUnit.HOURS);
        }
        if (!changedFields.isEmpty()) record(tenantId, TenantActivityDraft.of(TenantActivityType.LLM_SERVICE_UPDATED).actor(userId)
                .target(TenantActivityTargetType.LLM_SERVICE, serviceId, newName == null ? llmServiceDO.getName() : newName)
                .detail("changedFields", changedFields)
                .detail("credentialChanged", StrUtil.isNotBlank(requestParam.getApiKey())));
        return Boolean.TRUE;
    }

    /**
     * 判断本次请求是否包含模型服务表字段更新，避免标签或计费单独更新时生成空 SET SQL。
     */
    private boolean hasServiceFieldUpdate(LlmServiceUpdateReqDTO requestParam) {
        return StrUtil.isNotBlank(requestParam.getName())
                || StrUtil.isNotBlank(requestParam.getApiUrl())
                || StrUtil.isNotBlank(requestParam.getApiKey())
                || StrUtil.isNotBlank(requestParam.getModelName())
                || requestParam.getDescription() != null
                || requestParam.getRpm() != null
                || requestParam.getTpm() != null
                || requestParam.getFallbackServiceId() != null
                || requestParam.getContextWindow() != null
                || requestParam.getMaxOutputTokens() != null;
    }

    /**
     * 名称变更时的更新：加分布式锁并在锁内重查，保证并发下的名称唯一性。
     */
    private void checkNameUniqueAndUpdate(Long tenantId, Long serviceId, String newName,
                                          LlmServiceUpdateReqDTO requestParam) {
        RLock lock = redissonClient.getLock(RedisKeyConstant.LLM_LOCK_KEY + tenantId + ":" + newName);
        try {
            if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                throw new ClientException(LlmManageErrorCodeEnum.LLM_NAME_DUPLICATED);
            }
            try {
                Long dup = baseMapper.selectCount(Wrappers.lambdaQuery(LlmServiceDO.class)
                        .eq(LlmServiceDO::getTenantId, tenantId)
                        .eq(LlmServiceDO::getName, newName)
                        .ne(LlmServiceDO::getServiceId, serviceId)
                        .eq(LlmServiceDO::getDelFlag, 0));
                if (dup != null && dup > 0) {
                    throw new ClientException(LlmManageErrorCodeEnum.LLM_NAME_DUPLICATED);
                }
                doUpdateService(tenantId, serviceId, requestParam);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("llm name lock interrupt for name {}", newName, e);
            throw new ServerException(LlmManageErrorCodeEnum.THREAD_INTERRUPTED);
        } catch (AbstractException e) {
            throw e;
        } catch (Exception e) {
            log.error("update LLM error: tenantId {} serviceId {}", tenantId, serviceId, e);
            throw new ServerException(LlmManageErrorCodeEnum.LLM_UPDATE_FAILED);
        }
    }

    /**
     * 仅更新请求中非空的字段。
     */
    private void doUpdateService(Long tenantId, Long serviceId, LlmServiceUpdateReqDTO requestParam) {
        LambdaUpdateWrapper<LlmServiceDO> updateWrapper = Wrappers.lambdaUpdate(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId);
        if (StrUtil.isNotBlank(requestParam.getName())) {
            updateWrapper.set(LlmServiceDO::getName, requestParam.getName().trim());
        }
        if (StrUtil.isNotBlank(requestParam.getApiUrl())) {
            updateWrapper.set(LlmServiceDO::getApiUrl, requestParam.getApiUrl());
        }
        if (StrUtil.isNotBlank(requestParam.getModelName())) {
            updateWrapper.set(LlmServiceDO::getModelName, requestParam.getModelName());
        }
        if (StrUtil.isNotBlank(requestParam.getApiKey())) {
            updateWrapper.set(LlmServiceDO::getApiKey, aesUtil.encrypt(requestParam.getApiKey()));
        }
        if (requestParam.getDescription() != null) {
            updateWrapper.set(LlmServiceDO::getDescription, normalizeDescription(requestParam.getDescription()));
        }
        if (requestParam.getRpm() != null) {
            updateWrapper.set(LlmServiceDO::getRateLimitRpm, requestParam.getRpm() > 0 ? requestParam.getRpm() : null);
        }
        if (requestParam.getTpm() != null) {
            updateWrapper.set(LlmServiceDO::getRateLimitTpm, requestParam.getTpm() > 0 ? requestParam.getTpm() : null);
        }
        if (requestParam.getFallbackServiceId() != null) {
            Long fallbackServiceId = requestParam.getFallbackServiceId() > 0
                    ? requestParam.getFallbackServiceId()
                    : null;
            if (fallbackServiceId != null) {
                validateFallback(tenantId, serviceId, fallbackServiceId);
            }
            updateWrapper.set(LlmServiceDO::getFallbackServiceId, fallbackServiceId);
        }
        if (requestParam.getContextWindow() != null) {
            updateWrapper.set(LlmServiceDO::getContextWindow,
                    requestParam.getContextWindow() == 0 ? null : requestParam.getContextWindow());
        }
        if (requestParam.getMaxOutputTokens() != null) {
            updateWrapper.set(LlmServiceDO::getMaxOutputTokens,
                    requestParam.getMaxOutputTokens() == 0 ? null : requestParam.getMaxOutputTokens());
        }
        int update = baseMapper.update(updateWrapper);
        if (update < 1) {
            log.error("update LLM service error: tenantId {}, serviceId {}", tenantId, serviceId);
            throw new ServerException(LlmManageErrorCodeEnum.LLM_UPDATE_FAILED);
        }
    }

    /**
     * 模型介绍统一去除首尾空白，空白内容按未配置处理。
     */
    private String normalizeDescription(String description) {
        return StrUtil.isBlank(description) ? null : description.trim();
    }

    @Override
    public LlmServiceInfoRespDTO getLlmInfo(Long userId, Long tenantId, Long serviceId) {
        validateTenantAndMembership(userId, tenantId);
        // 从缓存中读取
        String cacheKey = RedisKeyConstant.LLM_SERVICE_INFO_KEY + serviceId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        LlmServiceDO llmServiceDO = JSON.parseObject(cachedJson, LlmServiceDO.class);
        boolean isReadFromCache = true;
        if (llmServiceDO == null) {
            isReadFromCache = false;
            llmServiceDO = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                    .eq(LlmServiceDO::getServiceId, serviceId)
                    .eq(LlmServiceDO::getTenantId, tenantId)
                    .eq(LlmServiceDO::getDelFlag, 0));
            if (llmServiceDO == null) {
                throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
            }
        }

        Long createdByUserId = llmServiceDO.getCreatedBy();
        UserDO createByUser = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, createdByUserId)
                .eq(UserDO::getStatus, 1)
                .eq(UserDO::getDelFlag, 0));
        String createUsername = createByUser != null ? createByUser.getUsername() : "已注销用户";
        String maskedApiKey = SensitiveUtil.maskApiKey(aesUtil.decrypt(llmServiceDO.getApiKey()));

        LlmServiceInfoRespDTO respDTO = new LlmServiceInfoRespDTO();
        BeanUtil.copyProperties(llmServiceDO, respDTO);
        respDTO.setCreatedByUsername(createUsername);
        respDTO.setApiKey(maskedApiKey);
        respDTO.setTagCodes(metadataService.tags(serviceId));
        respDTO.setPricing(pricingConfigurationService.current(serviceId));
        // 如果没命中缓存，就写回
        if (!isReadFromCache) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(llmServiceDO), 30, TimeUnit.MINUTES);
        }
        return respDTO;
    }

    private void validateTokenLimits(LlmServiceDO existing, Long contextWindow,
                                     Long maxOutputTokens, boolean create) {
        if (create && (notPositive(contextWindow) || notPositive(maxOutputTokens))) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_TOKEN_LIMIT_INVALID);
        }
        if (!create && (negative(contextWindow) || negative(maxOutputTokens))) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_TOKEN_LIMIT_INVALID);
        }
        Long finalContext = resolveTokenLimit(
                contextWindow, existing == null ? null : existing.getContextWindow()
        );
        Long finalOutput = resolveTokenLimit(
                maxOutputTokens, existing == null ? null : existing.getMaxOutputTokens()
        );
        if ((finalContext != null && finalContext <= 0) || (finalOutput != null && finalOutput <= 0)
                || (finalContext != null && finalOutput != null && finalOutput > finalContext)) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_TOKEN_LIMIT_INVALID);
        }
    }

    private boolean notPositive(Long value) {
        return value != null && value <= 0;
    }

    private boolean negative(Long value) {
        return value != null && value < 0;
    }

    private Long resolveTokenLimit(Long requested, Long existing) {
        if (requested == null) {
            return existing;
        }
        return requested == 0 ? null : requested;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean disableLlmService(Long userId, Long tenantId, Long serviceId) {
        validateTenantAndMembership(userId, tenantId);
        LlmServiceDO llmServiceDO = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (llmServiceDO == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
        } else if (llmServiceDO.getStatus() == 0) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_HAS_BEEN_DISABLED);
        }
        int update = baseMapper.update(Wrappers.lambdaUpdate(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0)
                .set(LlmServiceDO::getStatus, 0));
        if (update < 1) {
            log.error("disable llm failed: serviceId {}", serviceId);
            throw new ServerException(LlmManageErrorCodeEnum.LLM_DISABLE_FAILED);
        }
        record(tenantId, TenantActivityDraft.of(TenantActivityType.LLM_SERVICE_DISABLED).actor(userId)
                .target(TenantActivityTargetType.LLM_SERVICE, serviceId, llmServiceDO.getName()));
        // 删缓存
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_INFO_KEY + serviceId);
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_LIST_KEY + tenantId);
        stringRedisTemplate.opsForHash().delete(
                RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, llmServiceDO.getName()
        );
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean enableLlmService(Long userId, Long tenantId, Long serviceId) {
        validateTenantAndMembership(userId, tenantId);
        LlmServiceDO llmServiceDO = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (llmServiceDO == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
        } else if (llmServiceDO.getStatus() == 1) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_HAS_BEEN_ENABLED);
        }
        int update = baseMapper.update(Wrappers.lambdaUpdate(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0)
                .set(LlmServiceDO::getStatus, 1));
        if (update < 1) {
            log.error("enable llm failed: serviceId {}", serviceId);
            throw new ServerException(LlmManageErrorCodeEnum.LLM_ENABLE_FAILED);
        }
        record(tenantId, TenantActivityDraft.of(TenantActivityType.LLM_SERVICE_ENABLED).actor(userId)
                .target(TenantActivityTargetType.LLM_SERVICE, serviceId, llmServiceDO.getName()));
        llmServiceDO.setStatus(1);
        // 写回缓存
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_LIST_KEY + tenantId);
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.LLM_SERVICE_INFO_KEY + serviceId,
                JSON.toJSONString(llmServiceDO),
                30, TimeUnit.MINUTES);
        stringRedisTemplate.opsForHash().put(
                RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId,
                llmServiceDO.getName(),
                String.valueOf(serviceId)
        );
        stringRedisTemplate.expire(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, 3, TimeUnit.HOURS);
        return Boolean.TRUE;
    }

    @Override
    public LlmServiceRemovePreRespDTO prepareRemoveLlmService(Long userId, Long tenantId, Long serviceId) {
        validateTenantAndMembership(userId, tenantId);
        LlmServiceDO llmServiceDO = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (llmServiceDO == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
        }
        String removeLlmToken = jwtUtil.generateRemoveLlmToken(serviceId, tenantId, 5 * 60 * 1000L);
        Date expiresAt = new Date(System.currentTimeMillis() + 5 * 60 * 1000L);
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstant.LLM_REMOVE_TOKEN_KEY + serviceId,
                removeLlmToken,
                5,
                TimeUnit.MINUTES
        );

        return new LlmServiceRemovePreRespDTO(LLM_SERVICE_PREPARE_REMOVE_INFO, removeLlmToken, expiresAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeLlmService(Long userId, Long tenantId, Long serviceId, LlmServiceRemoveReqDTO requestParam) {
        validateTenantAndMembership(userId, tenantId);
        String requestToken = requestParam.getToken();
        String cachedToken = stringRedisTemplate.opsForValue().get(RedisKeyConstant.LLM_REMOVE_TOKEN_KEY + serviceId);
        if (cachedToken == null || !cachedToken.equals(requestToken)) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_REMOVE_TOKEN_EXPIRED);
        }
        boolean validateResult = jwtUtil.validateToken(requestToken);
        if (!validateResult) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_REMOVE_TOKEN_EXPIRED);
        }

        LlmServiceDO llmServiceDO = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (llmServiceDO == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
        }
        int update = baseMapper.update(Wrappers.lambdaUpdate(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0)
                .set(LlmServiceDO::getDelFlag, 1));
        if (update < 1) {
            // 寻找是否已经有过删除记录，如果有那就复用
            LlmServiceDO deletedRecord = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                    .eq(LlmServiceDO::getServiceId, serviceId)
                    .eq(LlmServiceDO::getTenantId, tenantId)
                    .eq(LlmServiceDO::getDelFlag, 1));
            if (deletedRecord == null) {
                log.error("remove llm failed: serviceId {}", serviceId);
                throw new ServerException(LlmManageErrorCodeEnum.LLM_REMOVE_FAILED);
            }
            baseMapper.update(Wrappers.lambdaUpdate(LlmServiceDO.class)
                    .eq(LlmServiceDO::getServiceId, serviceId)
                    .eq(LlmServiceDO::getTenantId, tenantId)
                    .eq(LlmServiceDO::getDelFlag, 1)
                    .set(LlmServiceDO::getUpdateTime, new Date()));
        }
        record(tenantId, TenantActivityDraft.of(TenantActivityType.LLM_SERVICE_REMOVED).actor(userId)
                .target(TenantActivityTargetType.LLM_SERVICE, serviceId, llmServiceDO.getName()));
        // 删除缓存
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_INFO_KEY + serviceId);
        stringRedisTemplate.delete(RedisKeyConstant.LLM_REMOVE_TOKEN_KEY + serviceId);
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_LIST_KEY + tenantId);
        stringRedisTemplate.opsForHash().delete(
                RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, llmServiceDO.getName()
        );
        return Boolean.TRUE;
    }

    @Override
    public Integer getLlmServiceCount(Long userId, Long tenantId) {
        validateTenantAndMembership(userId, tenantId);
        List<LlmServiceListRespDTO.LlmServiceInfo> infos = loadServiceInfos(tenantId);
        return infos.size();
    }

    @Override
    public List<com.yonagi.verse.dto.resp.TagInfoRespDTO> listTags() {
        return metadataService.catalogue();
    }

    /**
     * 校验备用模型：不能映射到自身，且必须是同租户内启用且未删除的服务。
     */
    private void validateFallback(Long tenantId, Long serviceId, Long fallbackServiceId) {
        if (serviceId.equals(fallbackServiceId)) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_FALLBACK_ID_INVALID);
        }
        LlmServiceDO fallback = baseMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, fallbackServiceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getStatus, 1)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (fallback == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_FALLBACK_INVALID);
        }
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

    private List<String> llmChangedFields(LlmServiceDO old, LlmServiceUpdateReqDTO request,
                                          List<String> oldTagCodes) {
        java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        if (StrUtil.isNotBlank(request.getName()) && !Objects.equals(old.getName(), request.getName().trim())) fields.add("name");
        if (StrUtil.isNotBlank(request.getApiUrl()) && !Objects.equals(old.getApiUrl(), request.getApiUrl())) fields.add("apiUrl");
        if (StrUtil.isNotBlank(request.getModelName()) && !Objects.equals(old.getModelName(), request.getModelName())) fields.add("modelName");
        if (request.getDescription() != null && !Objects.equals(old.getDescription(), normalizeDescription(request.getDescription()))) fields.add("description");
        if (request.getRpm() != null
                && !Objects.equals(old.getRateLimitRpm(), request.getRpm() > 0 ? request.getRpm() : null)) fields.add("rateLimitRpm");
        if (request.getTpm() != null
                && !Objects.equals(old.getRateLimitTpm(), request.getTpm() > 0 ? request.getTpm() : null)) fields.add("rateLimitTpm");
        if (request.getFallbackServiceId() != null
                && !Objects.equals(old.getFallbackServiceId(), request.getFallbackServiceId() > 0 ? request.getFallbackServiceId() : null)) fields.add("fallbackServiceId");
        if (request.getTagCodes() != null
                && !new java.util.LinkedHashSet<>(oldTagCodes == null ? List.of() : oldTagCodes)
                .equals(new java.util.LinkedHashSet<>(request.getTagCodes()))) fields.add("tags");
        if (request.getContextWindow() != null
                && !Objects.equals(old.getContextWindow(), request.getContextWindow() == 0 ? null : request.getContextWindow())) fields.add("contextWindow");
        if (request.getMaxOutputTokens() != null
                && !Objects.equals(old.getMaxOutputTokens(), request.getMaxOutputTokens() == 0 ? null : request.getMaxOutputTokens())) fields.add("maxOutputTokens");
        if (request.getPricing() != null) fields.add("pricing");
        if (StrUtil.isNotBlank(request.getApiKey())) fields.add("credential");
        return fields;
    }

    private void record(Long tenantId, TenantActivityDraft draft) {
        activityRecorder.record(tenantId, draft);
    }
}
