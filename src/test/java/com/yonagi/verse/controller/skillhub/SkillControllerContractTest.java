package com.yonagi.verse.controller.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.config.SecurityConfig;
import com.yonagi.verse.common.security.ApiKeyAuthenticationFilter;
import com.yonagi.verse.common.security.JwtAuthenticationFilter;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.SkillDownloadGrantRespDTO;
import com.yonagi.verse.dto.resp.SkillOptionsRespDTO;
import com.yonagi.verse.dto.resp.SkillDraftRespDTO;
import com.yonagi.verse.service.skillhub.SkillDownloadPayload;
import com.yonagi.verse.service.skillhub.SkillDownloadService;
import com.yonagi.verse.service.skillhub.SkillDraftService;
import com.yonagi.verse.service.skillhub.SkillImportTaskService;
import com.yonagi.verse.service.skillhub.SkillLifecycleService;
import com.yonagi.verse.service.skillhub.SkillPublicationService;
import com.yonagi.verse.service.skillhub.SkillQueryService;
import com.yonagi.verse.service.skillhub.SkillFileQueryService;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 实际MVC与Security路由契约；认证边界用替身，不声称验证真实JWT、Redis或数据库。 */
class SkillControllerContractTest {
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private SkillDraftService drafts;
    private SkillQueryService queries;
    private SkillDownloadService downloads;

    @BeforeAll
    static void setupMvc() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "verse.security.ignore-paths=/api/v1/skills/**", "verse.frontend-baseurl=http://localhost:3000");
        context.register(Wiring.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void closeMvc() {
        context.close();
    }

    @BeforeEach
    void dependencies() {
        drafts = context.getBean(SkillDraftService.class);
        queries = context.getBean(SkillQueryService.class);
        downloads = context.getBean(SkillDownloadService.class);
        reset(drafts, queries, downloads);
    }

    @Test
    void everySkillRouteRequiresAuthenticationEvenIfConfiguredInIgnorePaths() throws Exception {
        for (String path : List.of("/api/v1/skills", "/api/v1/skills/options", "/api/v1/skills/lookup",
                "/api/v1/skills/20", "/api/v1/skills/downloads/" + "a".repeat(64),
                "/api/v1/skills/me/publications",
                "/api/v1/skills/drafts/30", "/api/v1/skills/imports/50", "/api/v1/skills/submissions/60")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/skills/20/versions/70/download-grants")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/skills/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(drafts, queries, downloads);
        when(queries.options()).thenReturn(new SkillOptionsRespDTO(List.of(), List.of(), 20L, 50L, 500));
        mvc.perform(get("/api/v1/skills/options").header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.maxFiles").value(500))
                .andExpect(jsonPath("$.data.publishingEnabled").doesNotExist())
                .andExpect(jsonPath("$.data.downloadEnabled").doesNotExist());
    }

    @Test
    void missingLookupParameterAndInvalidNumericIdAreClientErrors() throws Exception {
        mvc.perform(get("/api/v1/skills/lookup").header("X-Test-Identity", "JWT-user").param("publisher", "author"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("A001701"));
        mvc.perform(get("/api/v1/skills/999999999999999999999999999").header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }

    @Test
    void draftUsesTrustedAccountDirectlyAndRejectsOwnershipInjection() throws Exception {
        when(drafts.create(eq(1L), any(), eq("command"))).thenReturn(new SkillDraftRespDTO(
                "30", "20", "test-skill", "EDITING", 0L, null, null, null, null, null, null, null,
                OffsetDateTime.now()));
        mvc.perform(post("/api/v1/skills/drafts").header("X-Test-Identity", "JWT-user")
                .header("Idempotency-Key", "command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"test-skill\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.draftId").value("30"));
        verify(drafts).create(eq(1L), any(), eq("command"));
        reset(drafts);
        mvc.perform(post("/api/v1/skills/drafts").header("X-Test-Identity", "JWT-user")
                .header("Idempotency-Key", "command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"test-skill\",\"userId\":2}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(drafts);
    }

    @Test
    void apiKeyCannotCreatePersonalDraftEvenIfAuthenticatedUpstream() throws Exception {
        mvc.perform(post("/api/v1/skills/drafts").header("X-Test-Identity", "API-key")
                .header("Idempotency-Key", "command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"test-skill\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(drafts);
    }

    @Test
    void independentPublisherSettingsAreNoLongerExposed() throws Exception {
        mvc.perform(get("/api/v1/skills/me/publisher").header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/skills/me/publisher").header("X-Test-Identity", "JWT-user")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(drafts);
    }

    @Test
    void authenticatedGrantStillRequiresTrustedJsonOriginAndDoesNotNeedIdempotencyKey() throws Exception {
        String path = "/api/v1/skills/20/versions/70/download-grants";
        mvc.perform(post(path).header("X-Test-Identity", "JWT-user")
                .contentType(MediaType.APPLICATION_JSON).header("Origin", "https://evil.invalid")
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("X-Test-Identity", "JWT-user").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(downloads);
        when(downloads.issue(eq(20L), eq(70L), any(), any())).thenReturn(new SkillDownloadGrantRespDTO(
                "/api/v1/skills/downloads/" + "a".repeat(64), OffsetDateTime.now().plusSeconds(60), "b".repeat(64), 3L));
        mvc.perform(post(path).header("X-Test-Identity", "JWT-user")
                .contentType(MediaType.APPLICATION_JSON).header("Origin", "http://localhost:3000")
                .content("{}")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().doesNotExist("Referrer-Policy"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.data.sha256").value("b".repeat(64)));
    }

    @Test
    void zipDownloadKeepsFileMetadataAndUsesDefaultSecurityHeaders() throws Exception {
        String token = "a".repeat(64);
        String filename = "技能包.zip";
        byte[] bytes = {0x50, 0x4b, 0x03, 0x04};
        doAnswer(invocation -> {
            Consumer<SkillDownloadPayload> send = invocation.getArgument(3);
            send.accept(new SkillDownloadPayload(filename, bytes, "b".repeat(64)));
            return null;
        }).when(downloads).download(eq(token), any(), any(), any());

        mvc.perform(get("/api/v1/skills/downloads/" + token).header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.length))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Referrer-Policy"))
                .andExpect(result -> assertEquals(filename, ContentDisposition.parse(
                        result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SkillQueryController.class, SkillDraftController.class, SkillDownloadController.class,
            SkillFileController.class,
            SkillExceptionAdvice.class, SkillRequestBodyAdvice.class, SkillOriginGuard.class,
            SkillDownloadSubjectResolver.class})
    static class Wiring {
        @Bean SkillQueryService queries() { return mock(SkillQueryService.class); }
        @Bean SkillFileQueryService fileQueries() {
            return mock(SkillFileQueryService.class);
        }
        @Bean SkillPublicQueryRateLimiter queryLimiter() { return mock(SkillPublicQueryRateLimiter.class); }
        @Bean SkillDraftService drafts() { return mock(SkillDraftService.class); }
        @Bean SkillPublicationService publications() { return mock(SkillPublicationService.class); }
        @Bean SkillLifecycleService lifecycle() { return mock(SkillLifecycleService.class); }
        @Bean SkillImportTaskService imports() { return mock(SkillImportTaskService.class); }
        @Bean SkillZipUploadReceiver receiver() { return mock(SkillZipUploadReceiver.class); }
        @Bean SkillDownloadService downloads() { return mock(SkillDownloadService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }

        @Bean
        JwtAuthenticationFilter jwt() throws Exception {
            JwtAuthenticationFilter filter = mock(JwtAuthenticationFilter.class);
            doAnswer(invocation -> {
                ServletRequest request = invocation.getArgument(0);
                ServletResponse response = invocation.getArgument(1);
                FilterChain chain = invocation.getArgument(2);
                String identity = ((HttpServletRequest) request).getHeader("X-Test-Identity");
                if (identity != null) {
                    UserContext user = new UserContext().setUserId(1L);
                    if ("API-key".equals(identity)) {
                        user.setApiKeyId(99L);
                    }
                    UserContextHolder.set(user);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("fixture", "", List.of()));
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    UserContextHolder.clear();
                    SecurityContextHolder.clearContext();
                }
                return null;
            }).when(filter).doFilter(any(), any(), any());
            return filter;
        }

        @Bean
        ApiKeyAuthenticationFilter apiKeys() throws Exception {
            ApiKeyAuthenticationFilter filter = mock(ApiKeyAuthenticationFilter.class);
            doAnswer(invocation -> {
                ((FilterChain) invocation.getArgument(2)).doFilter(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(filter).doFilter(any(), any(), any());
            return filter;
        }
    }
}
