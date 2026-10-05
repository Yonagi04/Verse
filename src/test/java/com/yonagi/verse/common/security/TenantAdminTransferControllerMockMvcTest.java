package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.controller.TenantAdminTransferController;
import com.yonagi.verse.service.impl.TenantAdminTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class TenantAdminTransferControllerMockMvcTest {
    @AfterEach
    void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void endpointUsesAuthenticatedUserAndReturnsResult() throws Exception {
        TenantAdminTransferService service = mock(TenantAdminTransferService.class);
        when(service.transfer(10L,20L,30L)).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new TenantAdminTransferController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addInterceptors(new TenantContextInterceptor(true))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
        mvc.perform(post("/api/v1/tenants/20/members/30/transfer-super-admin"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").value(true));
        verify(service).transfer(10L,20L,30L);
    }

    @Test
    void crossTenantRequestIsRejectedBeforeService() throws Exception {
        TenantAdminTransferService service = mock(TenantAdminTransferService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TenantAdminTransferController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addInterceptors(new TenantContextInterceptor(true))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(21L));
        mvc.perform(post("/api/v1/tenants/20/members/30/transfer-super-admin"))
                .andExpect(jsonPath("$.code").value("B000338"));
        verifyNoInteractions(service);
    }

    @Test
    void staleTargetReturnsDomainError() throws Exception {
        TenantAdminTransferService service = mock(TenantAdminTransferService.class);
        when(service.transfer(10L,20L,30L)).thenThrow(new ClientException(TenantErrorCodeEnum.TENANT_ADMIN_TRANSFER_INVALID));
        var mvc = MockMvcBuilders.standaloneSetup(new TenantAdminTransferController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
        mvc.perform(post("/api/v1/tenants/20/members/30/transfer-super-admin"))
                .andExpect(jsonPath("$.code").value("B000340"));
    }

    @Test
    void methodSecurityRejectsAdminMemberAndUnauthenticatedUsers() {
        TenantAdminTransferService service = mock(TenantAdminTransferService.class);
        ProxyFactory factory = new ProxyFactory(new TenantAdminTransferController(service));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        var controller = (TenantAdminTransferController) factory.getProxy();
        for (String role : List.of("ADMIN","MEMBER")) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "user", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            assertThrows(AccessDeniedException.class, () -> controller.transfer(10L,20L,30L));
        }
        SecurityContextHolder.clearContext();
        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                () -> controller.transfer(10L,20L,30L));
        verifyNoInteractions(service);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        controller.transfer(10L,20L,30L);
        verify(service).transfer(10L,20L,30L);
    }
}
