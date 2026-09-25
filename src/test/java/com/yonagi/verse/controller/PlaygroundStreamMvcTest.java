package com.yonagi.verse.controller;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.service.PlaygroundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class PlaygroundStreamMvcTest {
    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test
    void completedModelEventsReachBrowserAsNamedSse() throws Exception {
        PlaygroundService service = mock(PlaygroundService.class);
        Flux<ServerSentEvent<String>> events = Flux.just(
                ServerSentEvent.<String>builder("{\"turnId\":\"1\"}").event("accepted").build(),
                ServerSentEvent.<String>builder("{\"turnId\":\"1\",\"text\":\"你好\"}").event("delta").build(),
                ServerSentEvent.<String>builder("{\"turnId\":\"1\",\"reply\":\"你好\"}").event("completed").build());
        when(service.prepareSend(any(), eq(2L), eq(11L), eq("hello"), anyString()))
                .thenReturn(new PlaygroundService.PreparedTurn("request-1", events));
        UserContextHolder.set(new UserContext().setUserId(7L).setCurrentTenantId(2L));
        var mvc = MockMvcBuilders.standaloneSetup(new PlaygroundController(service)).build();
        var result = mvc.perform(post("/api/v1/tenants/2/playground/sessions/11/turns/stream")
                        .header("Idempotency-Key", "8f5e7338-6385-4f78-963e-889564109956")
                        .contentType("application/json").accept("text/event-stream")
                        .content("{\"prompt\":\"hello\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:accepted") || body.contains("event: accepted"));
        assertTrue(body.contains("event:delta") || body.contains("event: delta"));
        assertTrue(body.contains("event:completed") || body.contains("event: completed"));
        assertTrue(body.contains("\"text\":\"你好\""));
    }

    @Test
    void servletAsyncCompletionCancelsUpstreamFlux() throws Exception {
        PlaygroundService service = mock(PlaygroundService.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> events = Flux.just(ServerSentEvent.<String>builder("{}")
                        .event("accepted").build())
                .concatWith(Flux.<ServerSentEvent<String>>never())
                .doOnCancel(() -> cancelled.set(true));
        when(service.prepareSend(any(), eq(2L), eq(11L), eq("hello"), anyString()))
                .thenReturn(new PlaygroundService.PreparedTurn("request-1", events));
        UserContextHolder.set(new UserContext().setUserId(7L).setCurrentTenantId(2L));
        var mvc = MockMvcBuilders.standaloneSetup(new PlaygroundController(service)).build();
        var result = mvc.perform(post("/api/v1/tenants/2/playground/sessions/11/turns/stream")
                        .header("Idempotency-Key", "8f5e7338-6385-4f78-963e-889564109956")
                        .contentType("application/json").accept("text/event-stream")
                        .content("{\"prompt\":\"hello\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        result.getRequest().getAsyncContext().complete();
        assertTrue(cancelled.get(), "Servlet 完成后必须取消模型流订阅");
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:accepted") || body.contains("event: accepted"));
        assertTrue(body.contains("data:{}") || body.contains("data: {}"));
    }

    @Test
    void preflightFailureReturnsJsonBeforeSseStarts() throws Exception {
        PlaygroundService service = mock(PlaygroundService.class);
        when(service.prepareSend(any(), eq(2L), eq(11L), eq("hello"), anyString()))
                .thenThrow(new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE));
        UserContextHolder.set(new UserContext().setUserId(7L).setCurrentTenantId(2L));
        MockMvcBuilders.standaloneSetup(new PlaygroundController(service)).build()
                .perform(post("/api/v1/tenants/2/playground/sessions/11/turns/stream")
                        .header("Idempotency-Key", "8f5e7338-6385-4f78-963e-889564109956")
                        .contentType("application/json").accept("text/event-stream")
                        .content("{\"prompt\":\"hello\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("A001002"));
    }
}
