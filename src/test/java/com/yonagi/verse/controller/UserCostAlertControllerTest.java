package com.yonagi.verse.controller;

import com.yonagi.verse.service.user.costalert.UserCostAlertSettingsService;
import com.yonagi.verse.service.user.costalert.UserCostAlertQueryService;
import com.yonagi.verse.common.security.*;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserCostAlertControllerTest {
    final UserCostAlertQueryService query=mock(UserCostAlertQueryService.class);
    final UserCostAlertSettingsService configuration=mock(UserCostAlertSettingsService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        UserContextHolder.set(new UserContext().setUserId(99L));
        mvc=MockMvcBuilders.standaloneSetup(new UserCostAlertController(query,configuration)).setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void cleanup() { UserContextHolder.clear(); }
    @Test void noTenantIsNeededAndTrustedSelfIdentityReachesAllQueries() throws Exception {
        mvc.perform(get("/api/v1/users/me/cost-alert")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")); verify(query).getSettings(99L);
    }
    @Test void invalidInputCannotReachReplacementAndValidCompleteBodyUsesCurrentUser() throws Exception {
        mvc.perform(put("/api/v1/users/me/cost-alert").contentType(MediaType.APPLICATION_JSON).content("{\"daily\":{\"enabled\":true,\"thresholdFen\":\"0.1\"}}"))
                .andExpect(jsonPath("$.code").isNotEmpty()); verifyNoInteractions(configuration);
        mvc.perform(put("/api/v1/users/me/cost-alert").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedVersion":"0","daily":{"enabled":true,"thresholdFen":"1000"},"weekly":{"enabled":false,"thresholdFen":null},"channels":["EMAIL"]}
                """)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")); verify(configuration).replace(eq(99L),any());
    }
    @Test void unauthenticatedRequestDoesNotReachService() throws Exception {
        UserContextHolder.clear(); mvc.perform(get("/api/v1/users/me/cost-alert")).andExpect(jsonPath("$.code").isNotEmpty()); verifyNoInteractions(query);
    }
    @Test void numericMoneyAndUnknownOwnerFieldsCannotBeCoercedOrAccepted() throws Exception {
        for (String body:java.util.List.of(
                "{\"expectedVersion\":\"0\",\"daily\":{\"enabled\":true,\"thresholdFen\":1000},\"weekly\":{\"enabled\":false},\"channels\":[\"EMAIL\"]}",
                "{\"expectedVersion\":\"0\",\"userId\":1,\"daily\":{\"enabled\":true,\"thresholdFen\":\"1000\"},\"weekly\":{\"enabled\":false},\"channels\":[\"EMAIL\"]}")) {
            mvc.perform(put("/api/v1/users/me/cost-alert").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value("A001601"));
        }
        verifyNoInteractions(configuration);
    }

    @Test void malformedBodyUsesGlobalFormatErrorAndForgedQueryUsesSettingsError() throws Exception {
        mvc.perform(put("/api/v1/users/me/cost-alert").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(jsonPath("$.code").value("A000001")).andExpect(jsonPath("$.message").value("请求参数格式不正确"));
        mvc.perform(get("/api/v1/users/me/cost-alert").param("userId","1"))
                .andExpect(jsonPath("$.code").value("A001601")).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(query,configuration);
    }
}
