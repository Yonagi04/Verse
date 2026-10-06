package com.yonagi.verse.service.forward.impl;

import com.yonagi.verse.common.cache.NoQueryAccess;

import com.yonagi.verse.common.cache.QueryCached;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.LlmServiceCapabilityDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.AdapterRegistry;
import com.yonagi.verse.service.cache.LiveModelBindingCacheBehavior;
import com.yonagi.verse.service.cache.LiveModelRouteCacheBehavior;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;


import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

/**
 * 模型解析实现；完整路由结果由核心查询切面缓存，回源时按租户校验启用状态。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelResolverImpl implements ModelResolver {

    private final StringRedisTemplate stringRedisTemplate;
    private final LlmServiceMapper llmServiceMapper;
    private final LlmServiceCapabilityMapper capabilityMapper;
    private final AdapterRegistry adapterRegistry;

    @Override
    public void requireBinding(LlmServiceDO service, ModelOperation operation) {
        protocolFor(service, operation);
    }

    @Override
    @QueryCached(keyPrefix = LLM_SERVICE_PROTOCOL_KEY, seconds = HOURS_4, access = NoQueryAccess.class,
            tables = {"t_llm_service", "t_llm_service_capability", "t_user", "t_tenant"},
            behavior = LiveModelBindingCacheBehavior.class)
    public UpstreamProtocol protocolFor(LlmServiceDO service, ModelOperation operation) {
        if (service == null || operation == null) throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        requireAvailable(service);
        LlmServiceCapabilityDO binding = capabilityMapper.selectOne(Wrappers.lambdaQuery(LlmServiceCapabilityDO.class)
                .eq(LlmServiceCapabilityDO::getServiceId, service.getServiceId())
                .eq(LlmServiceCapabilityDO::getOperation, operation.name()));
        // 未完成回填的旧行仅保留原有 Chat 行为。
        if (binding == null && operation == ModelOperation.CHAT_COMPLETIONS) {
            var configured = capabilityMapper.selectList(Wrappers.lambdaQuery(LlmServiceCapabilityDO.class)
                    .eq(LlmServiceCapabilityDO::getServiceId, service.getServiceId()));
            if (configured != null && !configured.isEmpty()) {
                throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
            }
            adapterRegistry.select(service.getProvider(), operation, UpstreamProtocol.OPENAI_COMPAT);
            return UpstreamProtocol.OPENAI_COMPAT;
        }
        if (binding == null || !Integer.valueOf(1).equals(binding.getEnabled())) {
            throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        }
        try {
            UpstreamProtocol protocol = UpstreamProtocol.valueOf(binding.getUpstreamProtocol());
            adapterRegistry.select(service.getProvider(), operation, protocol);
            return protocol;
        } catch (IllegalArgumentException e) {
            throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        }
    }

    @Override
    @QueryCached(keyPrefix = LLM_SERVICE_INFO_KEY, seconds = HOURS_4, access = NoQueryAccess.class,
            tables = {"t_llm_service", "t_user", "t_tenant"},
            behavior = LiveModelRouteCacheBehavior.class)
    public LlmServiceDO resolve(Long tenantId, String model) {
        if (tenantId == null || !StringUtils.hasText(model)) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }

        Long serviceId = resolveServiceId(tenantId, model);
        if (serviceId == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }

        LlmServiceDO service = loadService(serviceId, tenantId);
        if (service == null || !model.equals(service.getName())
                || !Integer.valueOf(1).equals(service.getStatus())) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_CONFIGURED);
        }
        requireAvailable(service);
        return service;
    }

    /** 资源和提供者状态不能由配置缓存决定；备用模型、Playground 也经绑定校验进入此处。 */
    public void requireAvailable(LlmServiceDO service) {
        if (service == null || llmServiceMapper.countCallableService(service.getTenantId(), service.getServiceId()) != 1) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_CONFIGURED);
        }
    }

    /**
     * 回源时按租户和别名定位模型，不能读取旧的手工路由缓存。
     */
    private Long resolveServiceId(Long tenantId, String model) {
        LlmServiceDO service = llmServiceMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getName, model)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (service == null) {
            return null;
        }
        return service.getServiceId();
    }

    private LlmServiceDO loadService(Long serviceId, Long tenantId) {
        LlmServiceDO service = llmServiceMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        return service;
    }
}
