package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO;
import com.yonagi.verse.dto.resp.CostLimitConfig;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.budget.CostBudgetUnavailableException;
import com.yonagi.verse.service.budget.CostLimitExceededException;
import com.yonagi.verse.service.forward.InFlightRequestCoalescer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Publisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostBudgetRejectionPrivacyTest {
    @AfterEach void clearContext() { UserContextHolder.clear(); }

    @ParameterizedTest
    @CsvSource({"CHAT,false", "CHAT,true", "JSON,false", "JSON,true", "STREAM,false", "STREAM,true",
            "RESPONSES_STREAM,false", "RESPONSES_STREAM,true", "MEDIA,false", "MEDIA,true", "RERANK,false", "RERANK,true"})
    void costRejectionReturnsProtocolErrorWithoutFurtherBusinessCalls(String endpoint, boolean unavailable) {
        LlmForwardService service = mock(LlmForwardService.class);
        LlmForwardController controller = new LlmForwardController(service, new InFlightRequestCoalescer());
        UserContextHolder.set(new UserContext().setUserId(1L).setCurrentTenantId(2L).setApiKeyId(3L));
        AbstractException rejection = unavailable ? new CostBudgetUnavailableException() : exceeded();
        String body = "{\"model\":\"alias\"}";
        ResponseEntity<?> response;
        switch (endpoint) {
            case "CHAT", "JSON" -> {
                ModelOperation operation = "CHAT".equals(endpoint) ? ModelOperation.CHAT_COMPLETIONS : ModelOperation.EMBEDDINGS;
                doThrow(rejection).when(service).preflight(any(), eq(operation), eq(body));
                response = "CHAT".equals(endpoint) ? controller.chatCompletion(body) : controller.embeddings(body);
                verify(service).preflight(any(), eq(operation), eq(body));
            }
            case "STREAM" -> {
                when(service.chatCompletionStream(any(), anyString(), anyString(), any())).thenThrow(rejection);
                response = controller.chatCompletion("{\"model\":\"alias\",\"stream\":true}");
                verify(service).chatCompletionStream(any(), anyString(), anyString(), any());
            }
            case "RESPONSES_STREAM" -> {
                when(service.responsesStream(any(), anyString(), anyString(), any())).thenThrow(rejection);
                response = controller.responses("{\"model\":\"alias\",\"stream\":true}");
                verify(service).responsesStream(any(), anyString(), anyString(), any());
            }
            case "MEDIA" -> {
                when(service.media(any(), eq(ModelOperation.SPEECH), eq("alias"), any(), anyString(), any())).thenThrow(rejection);
                response = controller.speech(body);
                verify(service).media(any(), eq(ModelOperation.SPEECH), eq("alias"), any(), anyString(), any());
            }
            case "RERANK" -> {
                when(service.jsonCompletion(any(), eq(ModelOperation.RERANK), eq(body), anyString(), any())).thenThrow(rejection);
                response = new RerankController(service).rerank(body);
                verify(service).jsonCompletion(any(), eq(ModelOperation.RERANK), eq(body), anyString(), any());
            }
            default -> throw new IllegalArgumentException(endpoint);
        }
        assertEquals(unavailable ? 503 : 429, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        String requestId = response.getHeaders().getFirst("x-request-id");
        assertNotNull(requestId);
        String payload = response.getBody() instanceof Publisher<?> publisher
                ? (String) Flux.from(publisher).blockFirst() : (String) response.getBody();
        var error = JSON.parseObject(payload).getJSONObject("error");
        assertEquals(unavailable ? "cost_budget_unavailable" : "cost_limit_exceeded", error.getString("code"));
        assertEquals(requestId, error.getString("request_id"));
        if (unavailable) {
            assertNull(response.getHeaders().getFirst("Retry-After"));
            assertFalse(error.containsKey("retry_at"));
        } else {
            assertTrue(Long.parseLong(response.getHeaders().getFirst("Retry-After")) > 0);
            assertEquals("API_KEY", error.getString("scope"));
            assertEquals("DAY", error.getString("period"));
        }
        verifyNoMoreInteractions(service);
    }

    private CostLimitExceededException exceeded() {
        String retryAt = OffsetDateTime.now().plusDays(1).toString();
        return new CostLimitExceededException(new ApiKeyCostStatusRespDTO("3",
                new CostLimitConfig(true, "100", null, null, "1"), "CNY", "Asia/Shanghai", null,
                "READY", "LIMITED", retryAt, List.of(),
                List.of(new ApiKeyCostStatusRespDTO.Hit("API_KEY", "DAY", retryAt))));
    }
}
