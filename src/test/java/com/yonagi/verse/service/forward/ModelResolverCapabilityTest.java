package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dao.entity.LlmServiceCapabilityDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.service.forward.impl.ModelResolverImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelResolverCapabilityTest {
    @Test
    void cachedCustomServiceRetainsLegacyChatAndTenantIsolation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        LlmServiceMapper services = mock(LlmServiceMapper.class);
        LlmServiceCapabilityMapper capabilities = mock(LlmServiceCapabilityMapper.class);
        AdapterRegistry registry = new AdapterRegistry(List.of(
                registration(ModelOperation.CHAT_COMPLETIONS, UpstreamProtocol.OPENAI_COMPAT)));
        ModelResolverImpl resolver = new ModelResolverImpl(redis, services, capabilities, registry);
        LlmServiceDO service = service(1);
        when(redis.opsForHash().get(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + 2L, "alias"))
                .thenReturn("10");
        when(redis.opsForHash().get(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + 3L, "alias"))
                .thenReturn("10");
        when(redis.opsForValue().get(RedisKeyConstant.LLM_SERVICE_INFO_KEY + 10L))
                .thenReturn(JSON.toJSONString(service));
        assertEquals("unknown-provider", resolver.resolve(2L, "alias").getProvider());
        resolver.requireBinding(service, ModelOperation.CHAT_COMPLETIONS);
        assertThrows(ClientException.class, () -> resolver.requireBinding(service, ModelOperation.EMBEDDINGS));
        assertThrows(ClientException.class, () -> resolver.resolve(3L, "alias"));
        verify(services, never()).selectOne(any());
    }

    @Test
    void disabledCachedServiceDoesNotRoute() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        ModelResolverImpl resolver = new ModelResolverImpl(redis, mock(LlmServiceMapper.class),
                mock(LlmServiceCapabilityMapper.class), new AdapterRegistry(List.of()));
        when(redis.opsForHash().get(RedisKeyConstant.LLM_SERVICE_ROUTE_KEY + 2L, "alias"))
                .thenReturn("10");
        when(redis.opsForValue().get(RedisKeyConstant.LLM_SERVICE_INFO_KEY + 10L))
                .thenReturn(JSON.toJSONString(service(0)));
        assertThrows(ClientException.class, () -> resolver.resolve(2L, "alias"));
    }

    @Test
    void explicitEmbeddingsOnlyBindingNeverEnablesImplicitChat() {
        LlmServiceCapabilityMapper capabilities = mock(LlmServiceCapabilityMapper.class);
        LlmServiceCapabilityDO embedding = new LlmServiceCapabilityDO();
        embedding.setServiceId(10L);
        embedding.setOperation(ModelOperation.EMBEDDINGS.name());
        embedding.setUpstreamProtocol(UpstreamProtocol.OPENAI_COMPAT.name());
        embedding.setEnabled(1);
        when(capabilities.selectOne(any())).thenAnswer(invocation -> null);
        when(capabilities.selectList(any())).thenReturn(List.of(embedding));
        ModelResolverImpl resolver = new ModelResolverImpl(mock(StringRedisTemplate.class),
                mock(LlmServiceMapper.class), capabilities,
                new AdapterRegistry(List.of(registration(ModelOperation.CHAT_COMPLETIONS,
                        UpstreamProtocol.OPENAI_COMPAT))));
        assertThrows(ClientException.class, () -> resolver.requireBinding(service(1), ModelOperation.CHAT_COMPLETIONS));
    }

    private LlmServiceDO service(int status) {
        LlmServiceDO result = LlmServiceDO.builder().serviceId(10L).tenantId(2L).name("alias")
                .provider("unknown-provider").status(status).build();
        result.setDelFlag(0);
        return result;
    }

    private AdapterRegistration registration(ModelOperation operation, UpstreamProtocol protocol) {
        return new AdapterRegistration() {
            public ModelOperation operation() { return operation; }
            public UpstreamProtocol protocol() { return protocol; }
            public String provider() { return null; }
        };
    }
}
