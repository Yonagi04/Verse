package com.yonagi.verse.controller;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.CostReconciliationRespDTO;
import com.yonagi.verse.service.budget.CostReconciliationQueries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CostReconciliationControllerTest {
    private static final String PATH = "/api/v1/cost-reconciliation-batches/A1b2C3d4/cases";
    private final CostReconciliationQueries queries = mock(CostReconciliationQueries.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        UserContextHolder.set(new UserContext().setUserId(42L));
        mvc = MockMvcBuilders.standaloneSetup(new CostReconciliationController(queries)).build();
    }

    @AfterEach
    void cleanup() {
        UserContextHolder.clear();
    }

    @Test
    void batchPathUsesAuthenticatedAccountAndReturnsBothCounts() throws Exception {
        when(queries.page(42L, "A1b2C3d4", CostReconciliationQueries.State.RESOLVED, null))
                .thenReturn(new CostReconciliationRespDTO.Batch("A1b2C3d4", "1", "2026-11-08T00:00:00+08:00",
                        3, 2, null, List.of()));

        mvc.perform(get(PATH).param("state", "RESOLVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pendingCount").value(3)).andExpect(jsonPath("$.data.resolvedCount").value(2));
    }

    @Test
    void invalidTokenInputAndMissingIdempotencyDoNotReachCaseUseCase() throws Exception {
        mvc.perform(post(PATH + "/3/resolve").contentType(MediaType.APPLICATION_JSON).content("""
                {"decision":"CHARGE","evidenceConfirmed":true,"version":0,"inputTokens":-1}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }

    @Test
    void fractionalUsageCannotBeTruncatedIntoBillableTokens() throws Exception {
        mvc.perform(post(PATH + "/3/resolve").contentType(MediaType.APPLICATION_JSON).content("""
                {"decision":"CHARGE","evidenceConfirmed":true,"version":0,"submissionKey":"submission-1",
                 "inputTokens":1.5,"cachedTokens":0,"outputTokens":1}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }
}
