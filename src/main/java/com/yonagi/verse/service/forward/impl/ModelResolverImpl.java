package com.yonagi.verse.service.forward.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * 模型解析实现 — 命中 Redis 路由索引（name → serviceId），再取服务详情，
 * 校验启用状态后返回；路由索引未命中时回退 DB 按别名查询并回写索引。
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
    public UpstreamProtocol protocolFor(LlmServiceDO service, ModelOperation operation) {
        if (service == null || operation == null) throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
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
        return service;
    }

    /**
     * name → serviceId：优先 Redis 路由索引，未命中回退 DB（按租户 + 别名查启用服务）。
     */
    private Long resolveServiceId(Long tenantId, String model) {
        Object cached = stringRedisTemplate.opsForHash()
                .get(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, model);
        if (cached != null) {
            return Long.parseLong(cached.toString());
        }

        LlmServiceDO service = llmServiceMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getName, model)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (service == null) {
            return null;
        }
        // 回写索引，避免下次再走 DB
        stringRedisTemplate.opsForHash().put(
                RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, model, String.valueOf(service.getServiceId()));
        stringRedisTemplate.expire(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + tenantId, 3, TimeUnit.HOURS);
        return service.getServiceId();
    }

    private LlmServiceDO loadService(Long serviceId, Long tenantId) {
        String cacheKey = RedisKeyConstant.LLM_SERVICE_INFO_KEY + serviceId;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            LlmServiceDO service = JSON.parseObject(cached, LlmServiceDO.class);
            return service != null && tenantId.equals(service.getTenantId())
                    && Integer.valueOf(0).equals(service.getDelFlag()) ? service : null;
        }
        LlmServiceDO service = llmServiceMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0));
        if (service != null) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(service), 30, TimeUnit.MINUTES);
        }
        return service;
    }
}
