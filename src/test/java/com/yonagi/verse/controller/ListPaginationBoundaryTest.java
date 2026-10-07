package com.yonagi.verse.controller;

import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ListPaginationBoundaryTest {
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final LlmManageService models = mock(LlmManageService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApiKeyController(keys), new TenantController(tenants),
                        new LlmManageController(models))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(1L));
    }

    @AfterEach
    void clearContext() { UserContextHolder.clear(); }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidPages() {
        return Stream.of("/api/v1/api-keys/2/list", "/api/v1/tenants/2/members",
                "/api/v1/tenants/2/join-requests", "/api/v1/tenants/2/invites", "/api/v1/llm-service/2/list")
                .flatMap(path -> Stream.of(new int[]{0, 10}, new int[]{1, 0}, new int[]{1, -1},
                                new int[]{1, 101}, new int[]{1, Integer.MAX_VALUE})
                        .map(page -> org.junit.jupiter.params.provider.Arguments.of(path, page[0], page[1])));
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void rejectsInvalidPageBeforeCallingService(String path, int page, int size) throws Exception {
        mvc.perform(get(path).param("pageNum", String.valueOf(page)).param("pageSize", String.valueOf(size)))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("0")));
        verifyNoInteractions(keys, tenants, models);
    }

    static Stream<String> listPaths() {
        return Stream.of("/api/v1/api-keys/2/list", "/api/v1/tenants/2/members",
                "/api/v1/tenants/2/join-requests", "/api/v1/tenants/2/invites", "/api/v1/llm-service/2/list");
    }

    @ParameterizedTest
    @MethodSource("listPaths")
    void acceptsMaximumSizeAndLargePageWithoutClamping(String path) throws Exception {
        mvc.perform(get(path).param("pageNum", "2147483647").param("pageSize", "100"))
                .andExpect(jsonPath("$.code").value("0"));
        switch (path) {
            case "/api/v1/api-keys/2/list" -> verify(keys).listApiKeys(1L, 2L, Integer.MAX_VALUE, 100);
            case "/api/v1/tenants/2/members" -> verify(tenants).listTenantMembers(1L, 2L, Integer.MAX_VALUE, 100);
            case "/api/v1/tenants/2/join-requests" -> verify(tenants).listJoinRequests(1L, 2L, Integer.MAX_VALUE, 100);
            case "/api/v1/tenants/2/invites" -> verify(tenants).listTenantInviteCodes(1L, 2L, Integer.MAX_VALUE, 100);
            default -> verify(models).listLlmService(1L, 2L, Integer.MAX_VALUE, 100, null, null);
        }
    }
}
