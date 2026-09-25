package com.yonagi.verse.common.security;

import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.service.impl.ApiKeyUsageRecorder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyAuthenticationFilterTest {

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
    private static StringRedisTemplate redis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        return redis;
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/openai/chat/completions");
        request.addHeader("Authorization", authorization);
        return request;
    }
}
