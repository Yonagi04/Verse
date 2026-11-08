package com.yonagi.verse.controller;

import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.web.ExternalAuthHeadersFilter;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.dto.req.ExternalFlowStartReqDTO;
import com.yonagi.verse.dto.resp.ExternalAuthRespDTO.Start;
import com.yonagi.verse.service.external.ExternalAuthCookieOutput;
import com.yonagi.verse.service.external.ExternalAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExternalAuthControllerHeadersTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginAndBindingPublishCleanupAndBrowserProofInSeparateHeaders(boolean binding) throws Exception {
        ExternalAuthService service = mock(ExternalAuthService.class);
        ExternalAuthProperties properties = new ExternalAuthProperties();
        properties.setFrontendOrigin(binding ? "https://verse.example" : "http://localhost:3000");
        ExternalAuthResponse response = new ExternalAuthResponse(properties);
        when(service.start(any(ExternalFlowStartReqDTO.class), nullable(Long.class), any(HttpServletRequest.class),
                any(ExternalAuthCookieOutput.class))).thenAnswer(invocation -> {
                    Long expectedUser = binding ? 10L : null;
                    assertEquals(expectedUser, invocation.<Long>getArgument(1));
                    ExternalAuthCookieOutput cookies = invocation.getArgument(3);
                    cookies.write("verse_ext_legacy", "", Duration.ZERO);
                    cookies.write("verse_ext_flow", "browser-proof", Duration.ofMinutes(10));
                    return new Start("flow", "tab-proof", "https://provider.example/authorize", "2026-11-07T12:00:00Z");
                });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExternalAuthController(service, response),
                        new UserExternalAccountController(service, response))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .build();
        String path = binding ? "/api/v1/users/me/external-accounts/flows" : "/api/v1/auth/external/flows";
        UserContextHolder.set(new UserContext().setUserId(10L));
        try {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"google\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, contains(
                            allOf(startsWith("verse_ext_legacy=;"), containsString("Max-Age=0"),
                                    containsString("Path=/api/v1")),
                            allOf(startsWith("verse_ext_flow=browser-proof;"), containsString("Max-Age=600"),
                                    containsString("Path=/api/v1"), containsString("HttpOnly"),
                                    containsString("SameSite=Lax"), binding ? containsString("; Secure")
                                            : not(containsString("; Secure"))))))
                    .andExpect(jsonPath("$.data.flowToken").value("tab-proof"))
                    .andExpect(content().string(not(containsString("browser-proof"))));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void callbackFailureKeepsEarlyRedirectStatusAndAlreadyWrittenCookies() throws Exception {
        ExternalAuthService service = mock(ExternalAuthService.class);
        when(service.callback(eq("google"), any(HttpServletRequest.class), any(ExternalAuthCookieOutput.class)))
                .thenAnswer(invocation -> {
                    ExternalAuthCookieOutput cookies = invocation.getArgument(2);
                    cookies.write("verse_ext_first", "first-proof", Duration.ofMinutes(5));
                    cookies.write("verse_ext_second", "second-proof", Duration.ofMinutes(5));
                    throw new IllegalStateException("fixture callback cleanup failure");
                });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ExternalAuthController(service, new ExternalAuthResponse(new ExternalAuthProperties())))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/auth/external/callback/google"))
                .andExpect(status().isSeeOther())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, contains(
                        allOf(startsWith("verse_ext_first=first-proof;"), containsString("Max-Age=300")),
                        allOf(startsWith("verse_ext_second=second-proof;"), containsString("Max-Age=300")))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("B000001"));
    }

    @Test
    void callbackKeepsReferrerPolicyWhileJsonUsesDefaultHeaders() throws Exception {
        ExternalAuthService service = mock(ExternalAuthService.class);
        String destination = "https://verse.example/auth/external/callback#flow=example";
        when(service.callback(eq("google"), any(HttpServletRequest.class), any(ExternalAuthCookieOutput.class)))
                .thenReturn(destination);
        when(service.providers()).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ExternalAuthController(service, new ExternalAuthResponse(new ExternalAuthProperties())))
                .addFilters(new HeaderWriterFilter(List.of(new CacheControlHeadersWriter())),
                        new ExternalAuthHeadersFilter())
                .build();
        String callback = "/api/v1/auth/external/callback/google";
        mvc.perform(get("/verse" + callback).contextPath("/verse").servletPath(callback))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, destination))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));

        String providers = "/api/v1/auth/external/providers";
        mvc.perform(get("/verse" + providers).contextPath("/verse").servletPath(providers))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Referrer-Policy"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
    }
}
