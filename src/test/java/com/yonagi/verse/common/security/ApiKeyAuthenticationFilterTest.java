package com.yonagi.verse.common.security;

import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.service.impl.ApiKeyUsageRecorder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyAuthenticationFilterTest {

    @org.junit.jupiter.api.BeforeAll
    static void metadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "api-auth-test"),
                ApiKeyDO.class);
    }

    @Test
    void rerankRequiresApiKeyBeforeController() throws Exception {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(redis(), mock(ApiKeyMapper.class),
                mock(ApiKeyUsageRecorder.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rerank");
        request.setServletPath("/api/v1/rerank");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(false, filter.shouldNotFilter(request));
        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("missing key reached controller");
        });
        assertEquals(401, response.getStatus());
    }

    @Test
    void rerankValidKeyUsesKeyTenant() throws Exception {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ApiKeyDO key = new ApiKeyDO();
        key.setApiKeyId(30L);
        key.setTenantId(20L);
        key.setUserId(10L);
        key.setStatus(1);
        when(mapper.selectOne(any())).thenReturn(key);
        when(mapper.selectAuthState(eq(30L), any())).thenReturn(key);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(redis(), mapper,
                mock(ApiKeyUsageRecorder.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rerank");
        request.setServletPath("/api/v1/rerank");
        request.addHeader("Authorization", "Bearer sk_valid");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
        {
            assertEquals(20L, UserContextHolder.get().getCurrentTenantId());
            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals(true, SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        });
        assertNull(UserContextHolder.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rerankInvalidKeyDoesNotReachController() throws Exception {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(redis(), mock(ApiKeyMapper.class),
                mock(ApiKeyUsageRecorder.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rerank");
        request.setServletPath("/api/v1/rerank");
        request.addHeader("Authorization", "Bearer sk_invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("invalid key reached controller");
        });
        assertEquals(401, response.getStatus());
    }

    @Test
    void validKeySchedulesUsageBeforeForwarding() throws Exception {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ApiKeyUsageRecorder recorder = mock(ApiKeyUsageRecorder.class);
        ApiKeyDO key = new ApiKeyDO();
        key.setApiKeyId(30L);
        key.setTenantId(20L);
        key.setUserId(10L);
        key.setStatus(1);
        when(mapper.selectOne(any())).thenReturn(key);
        when(mapper.selectAuthState(eq(30L), any())).thenReturn(key);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(redis(), mapper, recorder);
        FilterChain chain = (request, response) -> {
            assertEquals(30L, UserContextHolder.get().getApiKeyId());
            verify(recorder).record(eq(30L), any(Date.class));
        };

        filter.doFilterInternal(request("Bearer sk_valid"), new MockHttpServletResponse(), chain);

        assertNull(UserContextHolder.get());
    }

    @Test
    void invalidKeyDoesNotScheduleUsage() throws Exception {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ApiKeyUsageRecorder recorder = mock(ApiKeyUsageRecorder.class);
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(redis(), mapper, recorder);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request("Bearer sk_invalid"), response, (req, resp) -> {
            throw new AssertionError("invalid key must not reach the controller");
        });

        assertEquals(401, response.getStatus());
        assertNotNull(response.getContentAsString());
        verifyNoInteractions(recorder);
    }

    @SuppressWarnings("unchecked")
    private static QueryCache redis() {
        QueryCache cache = mock(QueryCache.class);
        when(cache.read(any(), any(), any(), eq(Long.class), any(), anyLong(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(6)).get());
        when(cache.check(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        return cache;
    }

    @Test
    void cachedIdNeverAuthorizesRevokedKey() throws Exception {
        QueryCache cache = redis();
        when(cache.read(any(), any(), any(), eq(Long.class), any(), anyLong(), any())).thenReturn(30L);
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ApiKeyDO revoked = new ApiKeyDO();
        revoked.setApiKeyId(30L); revoked.setStatus(0);
        when(mapper.selectAuthState(eq(30L), any())).thenReturn(revoked);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiKeyAuthenticationFilter(cache, mapper, mock(ApiKeyUsageRecorder.class))
                .doFilterInternal(request("Bearer sk_valid"), response, (req, res) -> {
                    throw new AssertionError("revoked cached key reached controller");
                });
        assertEquals(401, response.getStatus());
        verify(mapper).selectAuthState(eq(30L), any());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void cacheFailureReturns503WithoutDatabaseFallback() throws Exception {
        QueryCache cache = redis();
        when(cache.read(any(), any(), any(), eq(Long.class), any(), anyLong(), any()))
                .thenThrow(new ServerException("cache unavailable"));
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiKeyAuthenticationFilter(cache, mapper, mock(ApiKeyUsageRecorder.class))
                .doFilterInternal(request("Bearer sk_valid"), response, (req, res) -> {
                    throw new AssertionError("unavailable cache reached controller");
                });
        assertEquals(503, response.getStatus());
        verifyNoInteractions(mapper);
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/openai/chat/completions");
        request.addHeader("Authorization", authorization);
        return request;
    }
}
