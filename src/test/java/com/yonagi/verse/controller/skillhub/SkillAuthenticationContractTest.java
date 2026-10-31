package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.security.JwtAuthenticationFilter;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.SkillPageRespDTO;
import java.util.List;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.skillhub.SkillDraftService;
import com.yonagi.verse.service.skillhub.SkillImportTaskService;
import com.yonagi.verse.service.skillhub.SkillLifecycleService;
import com.yonagi.verse.service.skillhub.SkillPublicationService;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实JWT过滤器与个人Controller，隔离会话存储及账号权威读取。 */
class SkillAuthenticationContractTest {
    private static final String PATH = "/api/v1/skills/me/publications";
    private final SkillLifecycleService lifecycle = mock(SkillLifecycleService.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final CurrentTenantStateService tenants = mock(CurrentTenantStateService.class);
    private ValueOperations<String, String> sessions;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void endpoint() {
        JwtUtil jwt = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(jwt.parseToken("fixture-token")).thenReturn(claims);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        sessions = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(sessions);
        when(sessions.get(anyString())).thenReturn("1");
        when(accounts.isAccountActive(1L)).thenReturn(true);
        SkillDraftController controller = new SkillDraftController(mock(SkillDraftService.class),
                mock(SkillPublicationService.class),
                lifecycle, mock(SkillImportTaskService.class),
                mock(SkillZipUploadReceiver.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new SkillExceptionAdvice())
                .addFilters(new JwtAuthenticationFilter(jwt, redis, tenants, accounts)).build();
    }

    @Test
    void personalEndpointUsesActiveSessionAccountWithoutResolvingTenant() throws Exception {
        when(lifecycle.mine(1L, 1)).thenReturn(new SkillPageRespDTO<>(1, 12, 0L, List.of()));
        mvc.perform(get(PATH).servletPath(PATH).header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        verify(lifecycle).mine(1L, 1);
        verifyNoInteractions(tenants);
        assertNull(UserContextHolder.get());
    }

    @Test
    void closedAccountCannotUseOtherwiseValidSession() throws Exception {
        when(accounts.isAccountActive(1L)).thenReturn(false);
        mvc.perform(get(PATH).servletPath(PATH).header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lifecycle, tenants);
        assertNull(UserContextHolder.get());
    }

    @Test
    void revokedSessionCannotReachAccountOrPersonalResource() throws Exception {
        when(sessions.get(anyString())).thenReturn(null);
        mvc.perform(get(PATH).servletPath(PATH).header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts, lifecycle, tenants);
        assertNull(UserContextHolder.get());
    }
}
