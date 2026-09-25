package com.yonagi.verse.common.security;

import com.yonagi.verse.dao.projection.CurrentTenantState;
import com.yonagi.verse.service.CurrentTenantStateService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    @Test
    void rerankUsesApiKeyAuthentication() {
        Fixture fixture = fixture(new CurrentTenantState());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rerank");
        request.setServletPath("/api/v1/rerank");
        assertTrue(fixture.filter.shouldNotFilter(request));
    }

    @Test
    void authenticatedUserWithoutTenantKeepsLoginButReceivesNoTenantAuthorities() throws Exception {
        Fixture fixture = fixture(new CurrentTenantState());
        AtomicReference<UserContext> captured = new AtomicReference<>();
        FilterChain chain = (request, response) -> {
            captured.set((UserContext) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().isEmpty());
        };

        fixture.filter.doFilterInternal(request(), new MockHttpServletResponse(), chain);

        assertEquals(10L, captured.get().getUserId());
        assertNull(captured.get().getCurrentTenantId());
        assertNull(captured.get().getRole());
    }

    @Test
    void sameTokenUsesLatestDatabaseTenantOnEveryRequest() throws Exception {
        CurrentTenantStateService stateService = mock(CurrentTenantStateService.class);
        when(stateService.resolveCurrentTenant(10L))
                .thenReturn(state(20L, "MEMBER"), state(30L, "ADMIN"));
        Fixture fixture = fixture(stateService);
        AtomicReference<Long> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> seen.set(UserContextHolder.get().getCurrentTenantId());

        fixture.filter.doFilterInternal(request(), new MockHttpServletResponse(), chain);
        assertEquals(20L, seen.get());
        fixture.filter.doFilterInternal(request(), new MockHttpServletResponse(), chain);
        assertEquals(30L, seen.get());
        verify(stateService, times(2)).resolveCurrentTenant(10L);
    }

    private static Fixture fixture(CurrentTenantState state) {
        CurrentTenantStateService service = mock(CurrentTenantStateService.class);
        when(service.resolveCurrentTenant(10L)).thenReturn(state);
        return fixture(service);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(CurrentTenantStateService stateService) {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("10");
        when(claims.get("username", String.class)).thenReturn("yonagi");
        when(jwtUtil.parseToken("token")).thenReturn(claims);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenReturn("10");
        return new Fixture(new JwtAuthenticationFilter(jwtUtil, redis, stateService));
    }

    private static CurrentTenantState state(Long tenantId, String role) {
        return new CurrentTenantState().setTenantId(tenantId).setRole(role);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants");
        request.addHeader("Authorization", "Bearer token");
        return request;
    }

    private record Fixture(JwtAuthenticationFilter filter) {
    }
}
