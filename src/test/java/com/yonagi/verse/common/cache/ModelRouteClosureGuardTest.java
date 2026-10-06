package com.yonagi.verse.common.cache;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.service.forward.AdapterRegistry;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.impl.ModelResolverImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRouteClosureGuardTest {
    @Test
    void cachedRouteAndProtocolCannotAuthorizeClosedProviders() throws Throwable {
        QueryCache cache = mock(QueryCache.class);
        LlmServiceMapper models = mock(LlmServiceMapper.class);
        LlmServiceDO snapshot = LlmServiceDO.builder().tenantId(20L).serviceId(30L).createdBy(1L)
                .name("alias").status(1).build();
        snapshot.setDelFlag(0);
        when(models.countCallableService(20L, 30L)).thenReturn(1);
        // 始终返回旧缓存，模拟缓存失效失败；授权必须由实时 SQL 决定。
        when(cache.get(anyString(), anyString(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(call -> call.<String>getArgument(0).endsWith("resolve") ? snapshot : UpstreamProtocol.OPENAI_COMPAT);
        var target = new ModelResolverImpl(mock(StringRedisTemplate.class), models,
                mock(LlmServiceCapabilityMapper.class), new AdapterRegistry(List.of()));
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(QueryCacheTestSupport.aspect(cache));
        ModelResolver proxy = factory.getProxy();
        assertEquals(snapshot, proxy.resolve(20L, "alias"));
        assertEquals(UpstreamProtocol.OPENAI_COMPAT, proxy.protocolFor(snapshot, ModelOperation.CHAT_COMPLETIONS));

        when(models.countCallableService(20L, 30L)).thenReturn(0);

        assertThrows(ClientException.class, () -> proxy.resolve(20L, "alias"));
        assertThrows(ClientException.class, () -> proxy.protocolFor(snapshot, ModelOperation.CHAT_COMPLETIONS));
        verify(models, never()).selectOne(any());
    }
}
