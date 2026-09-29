package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.controller.TenantOverviewController;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO;
import com.yonagi.verse.dto.resp.TenantPreferenceRespDTO;
import com.yonagi.verse.service.TenantOverviewService;
import com.yonagi.verse.service.TenantPreferenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class TenantOverviewControllerMockMvcTest {
    @AfterEach
    void clear() { UserContextHolder.clear(); }

    @Test
    void otherTenantOverviewAndOwnPreferenceUseServiceAuthorization() throws Exception {
        TenantOverviewService overview = mock(TenantOverviewService.class);
        TenantPreferenceService preference = mock(TenantPreferenceService.class);
        TenantOverviewRespDTO.Detail detail = new TenantOverviewRespDTO.Detail();
        detail.setTenantId(21L);
        when(overview.detail(10L, 21L)).thenReturn(detail);
        TenantPreferenceRespDTO saved = new TenantPreferenceRespDTO();
        saved.setTenantId(21L);
        saved.setFavorite(true);
        when(preference.update(eq(10L), eq(21L), any())).thenReturn(saved);
        var mvc = MockMvcBuilders.standaloneSetup(new TenantOverviewController(overview, preference))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addInterceptors(new TenantContextInterceptor(true))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));

        mvc.perform(get("/api/v1/tenants/21/overview"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.tenantId").value("21"));
        mvc.perform(put("/api/v1/tenants/21/preference").contentType("application/json")
                        .content("{\"favorite\":true,\"pinned\":false}"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.favorite").value(true));
        verify(overview).detail(10L, 21L);
        verify(preference).update(eq(10L), eq(21L), any());
    }

    @Test
    void invalidTargetCannotBeDowngradedToEmptyOverview() throws Exception {
        TenantOverviewService overview = mock(TenantOverviewService.class);
        when(overview.detail(10L, 21L)).thenThrow(new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED));
        var mvc = MockMvcBuilders.standaloneSetup(new TenantOverviewController(overview, mock(TenantPreferenceService.class)))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addInterceptors(new TenantContextInterceptor(true))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));

        mvc.perform(get("/api/v1/tenants/21/overview"))
                .andExpect(jsonPath("$.code").value("B000308"));
    }
}
