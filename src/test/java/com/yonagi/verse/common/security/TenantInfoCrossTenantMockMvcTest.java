package com.yonagi.verse.common.security;

import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.controller.TenantController;
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
import com.yonagi.verse.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class TenantInfoCrossTenantMockMvcTest {

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void infoCanReadJoinedTargetWithoutSwitchingWhileSettingsStayScoped() throws Exception {
        TenantService service = mock(TenantService.class);
        TenantInfoRespDTO detail = new TenantInfoRespDTO();
        detail.setTenantId(21L);
        detail.setName("目标租户");
        when(service.getTenantInfo(10L, 21L)).thenReturn(detail);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TenantController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addInterceptors(new TenantContextInterceptor(true))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));

        mvc.perform(get("/api/v1/tenants/21/info"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.name").value("目标租户"));
        mvc.perform(get("/api/v1/tenants/21/settings"))
                .andExpect(jsonPath("$.code").value("B000338"));

        verify(service).getTenantInfo(10L, 21L);
        verify(service, never()).getTenantSettings(any(), any());
    }
}
