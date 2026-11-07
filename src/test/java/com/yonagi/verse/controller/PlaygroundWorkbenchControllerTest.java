package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.config.WebMvcConfig;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.TenantContextInterceptor;
import com.yonagi.verse.service.PlaygroundWorkbenchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaygroundWorkbenchControllerTest {
    private final PlaygroundWorkbenchService service = mock(PlaygroundWorkbenchService.class);
    private final MockMvc mvc = createMvc();

    @BeforeEach
    void setContext() {
        UserContextHolder.set(new UserContext().setUserId(7L).setCurrentTenantId(2L));
    }

    private MockMvc createMvc() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        WebMvcConfig config = new WebMvcConfig(new CurrentUserArgumentResolver(), new TenantContextInterceptor(true));
        config.extendMessageConverters(List.of(converter));
        return MockMvcBuilders.standaloneSetup(new PlaygroundWorkbenchController(service))
                .setMessageConverters(converter).build();
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.00000000000000000001", "2147483647.00000000000001", "2147483648",
            "0", "-1", "1.5", "\"1\"", "true", "null"})
    void restoreRejectsInvalidVersionBeforeHttpBindingCanRoundIt(String value) throws Exception {
        mvc.perform(post("/api/v1/tenants/2/playground/workbench/presets/11/restore")
                        .contentType("application/json").content("{\"version\":" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
        verifyNoInteractions(service);
    }

    @Test
    void restoreStillRequiresVersion() throws Exception {
        mvc.perform(post("/api/v1/tenants/2/playground/workbench/presets/11/restore")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlaygroundErrorCodeEnum.INVALID_CONFIG.code()));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"1,1,application/json", "1.0,1,application/json", "1e0,1,application/vnd.verse+json",
            "2147483647,2147483647,application/json"})
    void restorePreservesMathematicalIntegers(String value, int expected, String contentType) throws Exception {
        when(service.restore(any(), eq(2L), eq(11L), eq(expected)))
                .thenReturn(JSONObject.of("id", "11", "version", expected));
        mvc.perform(post("/api/v1/tenants/2/playground/workbench/presets/11/restore")
                        .contentType(contentType).content("{\"version\":" + value + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(expected));
        verify(service).restore(UserContextHolder.get(), 2L, 11L, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "fork"})
    void nestedConfigReachesUseCaseWithoutDecimalRounding(String operation) throws Exception {
        JSONObject response = JSONObject.of("id", "11");
        String payload = "{\"title\":\"中文\",\"revision\":1.00000000000000000001,\"config\":{\"lanes\":["
                + "{\"laneId\":\"00000000-0000-0000-0000-000000000001\",\"serviceId\":\"9\","
                + "\"config\":{\"maxTokens\":1.00000000000000000001}}]}}";
        String root = "/api/v1/tenants/2/playground/workbench/groups";
        switch (operation) {
            case "create" -> when(service.create(any(), eq(2L), eq("GROUP"), any())).thenReturn(response);
            case "update" -> {
                when(service.detail(any(), eq(2L), eq(11L))).thenReturn(JSONObject.of("kind", "GROUP"));
                when(service.update(any(), eq(2L), eq(11L), any())).thenReturn(response);
            }
            case "fork" -> when(service.fork(any(), eq(2L), eq(11L), any())).thenReturn(response);
            default -> throw new IllegalArgumentException(operation);
        }
        mvc.perform((operation.equals("update") ? put(root + "/11")
                        : post(operation.equals("fork") ? root + "/11/fork" : root))
                        .contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("11"));
        ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
        switch (operation) {
            case "create" -> verify(service).create(any(), eq(2L), eq("GROUP"), body.capture());
            case "update" -> verify(service).update(any(), eq(2L), eq(11L), body.capture());
            case "fork" -> verify(service).fork(any(), eq(2L), eq(11L), body.capture());
            default -> throw new IllegalArgumentException(operation);
        }
        BigDecimal expected = new BigDecimal("1.00000000000000000001");
        assertEquals(expected, assertInstanceOf(BigDecimal.class, body.getValue().get("revision")));
        Object tokens = body.getValue().getJSONObject("config").getJSONArray("lanes")
                .getJSONObject(0).getJSONObject("config").get("maxTokens");
        assertEquals(expected, assertInstanceOf(BigDecimal.class, tokens));
        assertEquals("中文", body.getValue().getString("title"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{version:1}"})
    void malformedOrNonObjectJsonKeepsMvcRejection(String body) throws Exception {
        mvc.perform(post("/api/v1/tenants/2/playground/workbench/presets/11/restore")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
