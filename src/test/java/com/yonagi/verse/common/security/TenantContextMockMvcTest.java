package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantContextMockMvcTest {

    private final MatrixController controller = new MatrixController();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new TenantContextInterceptor(true))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void sixTenantBusinessControllerFamiliesAreRejectedBeforeBusinessMethod() throws Exception {
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
        List<String> paths = List.of(
                "/api/v1/tenants/99/info",
                "/api/v1/api-keys/99/list",
                "/api/v1/llm-service/99/list",
                "/api/v1/audit/99/list",
                "/api/v1/usage/99/dashboard",
                "/api/v1/usage-events/99/reconciliation");

        for (String path : paths) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("B000338"));
        }
        assertEquals(0, controller.invocations.get());
    }

    @Test
    void joinedAndUnknownOtherTenantHaveIndistinguishableResponse() throws Exception {
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));

        mockMvc.perform(get("/api/v1/tenants/21/info"))
                .andExpect(jsonPath("$.code").value("B000338"))
                .andExpect(jsonPath("$.message").value("租户上下文已变化，请刷新后重试"));
        mockMvc.perform(get("/api/v1/tenants/999/info"))
                .andExpect(jsonPath("$.code").value("B000338"))
                .andExpect(jsonPath("$.message").value("租户上下文已变化，请刷新后重试"));
    }

    @Test
    void controlPlaneNotificationsAndOpenAiRoutesAreNotBlocked() throws Exception {
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
        List<String> paths = List.of(
                "/api/v1/tenants",
                "/api/v1/users/me",
                "/api/v1/notifications",
                "/api/v1/llm-service/tags",
                "/api/v1/openai/models");

        for (String path : paths) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0"));
        }
        assertEquals(paths.size(), controller.invocations.get());
    }

    @RestController
    static class MatrixController {
        private final AtomicInteger invocations = new AtomicInteger();

        @GetMapping({
                "/api/v1/tenants/{tenantId}/info",
                "/api/v1/api-keys/{tenantId}/list",
                "/api/v1/llm-service/{tenantId}/list",
                "/api/v1/audit/{tenantId}/list",
                "/api/v1/usage/{tenantId}/dashboard",
                "/api/v1/usage-events/{tenantId}/reconciliation"
        })
        Result<Boolean> business(@PathVariable Long tenantId) {
            invocations.incrementAndGet();
            return Results.success(true);
        }

        @GetMapping({
                "/api/v1/tenants",
                "/api/v1/users/me",
                "/api/v1/notifications",
                "/api/v1/llm-service/tags",
                "/api/v1/openai/models"
        })
        Result<Boolean> controlPlane() {
            invocations.incrementAndGet();
            return Results.success(true);
        }
    }
}
