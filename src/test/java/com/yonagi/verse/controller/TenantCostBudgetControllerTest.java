package com.yonagi.verse.controller;

import com.yonagi.verse.common.security.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.budget.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantCostBudgetControllerTest {
    final TenantCostBudgetConfigurationService budgets = mock(TenantCostBudgetConfigurationService.class);
    final CostNotificationReadinessService readiness = mock(CostNotificationReadinessService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        UserContextHolder.set(new UserContext().setUserId(1L));
        mvc = MockMvcBuilders.standaloneSetup(new TenantCostBudgetController(budgets,readiness)).build();
    }
    @AfterEach void cleanup() { UserContextHolder.clear(); }
    @Test void memberStatusNoStoreAndNoSensitiveFields() throws Exception {
        when(budgets.status(1L,2L)).thenReturn(new TenantCostStatusRespDTO.Summary("2","SUMMARY","LIMITED","2026-10-18T00:00:00+08:00",
                "Asia/Shanghai","2026-10-17T15:00:00+08:00",List.of(new ApiKeyCostStatusRespDTO.Hit("TENANT","DAY","2026-10-18T00:00:00+08:00")),"联系管理员"));
        mvc.perform(get("/api/v1/tenants/2/cost-status")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.visibility").value("SUMMARY")).andExpect(jsonPath("$.data.costLimit").doesNotExist())
                .andExpect(jsonPath("$.data.costWarning").doesNotExist()).andExpect(jsonPath("$.data.periods").doesNotExist());
        verify(budgets).status(1L,2L);
    }
    @Test void missingVersionAndInvalidBaseInputNeverReachSaveUseCase() throws Exception {
        mvc.perform(post("/api/v1/tenants/2/settings/save").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(budgets);
    }
    @Test void pendingConfirmationIsSuccessfulProtocolResponseWithoutSavedSnapshot() throws Exception {
        when(budgets.save(eq(1L),eq(2L),any())).thenReturn(new TenantSettingsSaveRespDTO("CONFIRMATION_REQUIRED",true,"确认","proof",
                "2026-10-17T15:05:00+08:00","3","4",List.of(),null,null));
        mvc.perform(post("/api/v1/tenants/2/settings/save").contentType(MediaType.APPLICATION_JSON).content("""
                {"settingsVersion":"3","name":"team","joinApprovalMode":0,"auditEnabled":false,"activityRecordingEnabled":false}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMATION_REQUIRED")).andExpect(jsonPath("$.data.savedSnapshot").isEmpty());
    }
}
