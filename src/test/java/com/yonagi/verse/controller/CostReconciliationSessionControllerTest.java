package com.yonagi.verse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.common.security.CostReconciliationSessionRoutes;
import com.yonagi.verse.common.security.JwtAuthenticationFilter;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.dto.resp.CostReconciliationRespDTO;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.LoginSessionValidationService;
import com.yonagi.verse.service.LoginSessionValidationService.Identity;
import com.yonagi.verse.service.budget.CostReconciliationQueries;
import com.yonagi.verse.service.budget.CostReconciliationSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证两个交接端点、认证接入及现有批次读取的 HTTP 边界。 */
class CostReconciliationSessionControllerTest {
    private static final String MAIN = "http://localhost:3000";
    private static final String RECONCILIATION = "http://reconciliation.localhost:3000";
    private static final String BATCH = "VCGkO9s2";
    private static final String CASES = CostReconciliationSessionRoutes.BATCHES_PREFIX + BATCH + "/cases";
    private static final String HANDOFF = CostReconciliationSessionRoutes.HANDOFF_PATH;
    private static final String EXCHANGE = CostReconciliationSessionRoutes.EXCHANGE_PATH;
    private static final String PROOF = "A".repeat(43);
    private static final String CREDENTIAL = "crs_" + PROOF;
    private final Identity parent = new Identity(42L, "member", "session", "parent-hash", 60_000L);
    private final LoginSessionValidationService logins = mock(LoginSessionValidationService.class);
    private final CurrentTenantStateService tenants = mock(CurrentTenantStateService.class);
    private final CostReconciliationSessionService sessions = mock(CostReconciliationSessionService.class);
    private final CostReconciliationQueries queries = mock(CostReconciliationQueries.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(logins.authenticate("main-jwt")).thenReturn(parent);
        when(sessions.authenticate(CREDENTIAL, null)).thenThrow(new ClientException(BaseErrorCode.TOKEN_INVALID));
        mvc = MockMvcBuilders.standaloneSetup(new CostReconciliationSessionController(sessions, json, MAIN,
                        RECONCILIATION), new CostReconciliationController(queries))
                .addFilters(new JwtAuthenticationFilter(logins, tenants, sessions)).build();
    }

    @Test
    void issueUsesVerifiedMainIdentityAndServerSelectedTarget() throws Exception {
        when(sessions.issue(parent, BATCH, PROOF)).thenReturn(PROOF);
        mvc.perform(issueRequest(MAIN)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.code").value(PROOF))
                .andExpect(jsonPath("$.data.target").value(RECONCILIATION + "/" + BATCH));
        verifyNoInteractions(tenants);
    }

    @Test
    void exchangeUsesAnonymousPostFromConfiguredOrigin() throws Exception {
        when(sessions.exchange(BATCH, PROOF, PROOF))
                .thenReturn(new CostReconciliationSessionService.Exchange(CREDENTIAL, 60_000L));
        mvc.perform(exchangeRequest(RECONCILIATION)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.token").value(CREDENTIAL));
        verifyNoInteractions(logins, tenants);
    }

    @Test
    void productionOriginCanUseHttpsAndCustomPort() throws Exception {
        MockMvc production = MockMvcBuilders.standaloneSetup(new CostReconciliationSessionController(sessions, json,
                "https://verse.example", "https://reconciliation.verse.example:8443"))
                .addFilters(new JwtAuthenticationFilter(logins, tenants, sessions)).build();
        when(sessions.issue(parent, BATCH, PROOF)).thenReturn(PROOF);
        production.perform(issueRequest("https://verse.example")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target").value("https://reconciliation.verse.example:8443/" + BATCH));
        production.perform(issueRequest("https://verse.example:8443")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://untrusted.example", "http://reconciliation.localhost:3000", "file:///tmp/verse",
            "https://verse.example:443", "https://verse.example:65536", "https://user@verse.example",
            "https://verse.example/continue", "https://Verse.example"})
    void malformedOrInsecureDeploymentOriginsCannotIssueRedirects(String invalidOrigin) {
        assertThrows(IllegalArgumentException.class,
                () -> new CostReconciliationSessionController(sessions, json, invalidOrigin, RECONCILIATION));
    }

    @Test
    void reusedGrantAndRevokedParentHaveDifferentFailureSemantics() throws Exception {
        when(sessions.exchange(BATCH, PROOF, PROOF))
                .thenThrow(new ClientException(CostReconciliationErrorCode.HANDOFF_INVALID))
                .thenThrow(new ClientException(BaseErrorCode.TOKEN_INVALID));
        mvc.perform(exchangeRequest(RECONCILIATION)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CostReconciliationErrorCode.HANDOFF_INVALID.code()));
        mvc.perform(exchangeRequest(RECONCILIATION)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongOriginsAndOversizedBodyWithoutIssuingOrExchanging() throws Exception {
        mvc.perform(issueRequest(RECONCILIATION)).andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mvc.perform(exchangeRequest(MAIN)).andExpect(status().isForbidden());
        mvc.perform(exchangeRequest(RECONCILIATION).content(" ".repeat(513)))
                .andExpect(status().isBadRequest());
        mvc.perform(exchangeRequest(RECONCILIATION).content("{}{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(exchangeRequest(RECONCILIATION).content("null"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
    }

    @Test
    void expiredMainCredentialReturns401AndRateLimitDoesNotLookLikeExpiredLogin() throws Exception {
        when(logins.authenticate("main-jwt")).thenThrow(new ClientException(BaseErrorCode.TOKEN_EXPIRED));
        mvc.perform(issueRequest(MAIN)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(BaseErrorCode.TOKEN_EXPIRED.code()));
        when(logins.authenticate("valid-jwt")).thenReturn(parent);
        when(sessions.issue(parent, BATCH, PROOF))
                .thenThrow(new ClientException(CostReconciliationErrorCode.HANDOFF_RATE_LIMITED));
        mvc.perform(post(HANDOFF).servletPath(HANDOFF).header(HttpHeaders.ORIGIN, MAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-jwt").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("batchCode", BATCH, "challenge", PROOF))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void scopedBearerUsesParentAccountForBatchAndRejectsOtherRoutes() throws Exception {
        when(sessions.authenticate(CREDENTIAL, BATCH)).thenReturn(parent);
        when(queries.page(42L, BATCH, CostReconciliationQueries.State.PENDING, null))
                .thenReturn(new CostReconciliationRespDTO.Batch(BATCH, "1", "2026-11-08T21:30:00+08:00",
                        0, 0, null, List.of()));
        mvc.perform(get(CASES).servletPath(CASES).header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value(BATCH));
        mvc.perform(post(CASES).servletPath(CASES).header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").servletPath("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ws").servletPath("/ws").header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(HANDOFF).servletPath(HANDOFF).header(HttpHeaders.ORIGIN, MAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tenants);
    }

    @Test
    void scopedBearerAllowsResolveButDoesNotAuthorizeAdditionalOperations() throws Exception {
        String resolve = CASES + "/42/resolve";
        when(sessions.authenticate(CREDENTIAL, BATCH)).thenReturn(parent);
        when(queries.resolve(eq(42L), eq(BATCH), eq(42L), any(CostReconciliationResolveReqDTO.class)))
                .thenReturn(new CostReconciliationRespDTO.Case("42", "request", null, null, "PLAYGROUND",
                        "2026-11-08T21:30:00+08:00", "RESOLVED", "CHARGEABILITY_UNKNOWN", 1, false, null, null));
        mvc.perform(post(resolve).servletPath(resolve).header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"decision":"NO_CHARGE","version":0,"evidenceConfirmed":true,
                                 "submissionKey":"fixture-1"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("RESOLVED"));
        mvc.perform(delete(CASES + "/42").servletPath(CASES + "/42")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void scopedAuthDependencyFailureDoesNotReportExpiredLogin() throws Exception {
        when(sessions.authenticate(CREDENTIAL, BATCH)).thenThrow(new DataAccessResourceFailureException("offline"));
        mvc.perform(get(CASES).servletPath(CASES).header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(queries);
    }

    private MockHttpServletRequestBuilder issueRequest(String origin) throws Exception {
        return post(HANDOFF).servletPath(HANDOFF).header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.AUTHORIZATION, "Bearer main-jwt").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("batchCode", BATCH, "challenge", PROOF)));
    }

    private MockHttpServletRequestBuilder exchangeRequest(String origin) throws Exception {
        return post(EXCHANGE).servletPath(EXCHANGE).header(HttpHeaders.ORIGIN, origin)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("batchCode", BATCH,
                        "code", PROOF, "verifier", PROOF)));
    }

}
