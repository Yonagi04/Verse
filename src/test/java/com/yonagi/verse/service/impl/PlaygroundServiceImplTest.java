package com.yonagi.verse.service.impl;

import com.yonagi.verse.service.playground.PlaygroundAccessPolicy;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.LlmServiceCapabilityDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.PlaygroundSessionDO;
import com.yonagi.verse.dao.entity.PlaygroundTurnDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.budget.CostBudgetDecision;
import com.yonagi.verse.service.budget.CostBudgetUnavailableException;
import com.yonagi.verse.service.budget.CostLimitExceededException;
import com.yonagi.verse.service.forward.ChatMessage;
import com.yonagi.verse.service.forward.ModelResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaygroundServiceImplTest {
    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserTenantMapper membershipMapper = mock(UserTenantMapper.class);
    private final LlmServiceMapper serviceMapper = mock(LlmServiceMapper.class);
    private final LlmServiceCapabilityMapper capabilityMapper = mock(LlmServiceCapabilityMapper.class);
    private final PlaygroundSessionMapper sessionMapper = mock(PlaygroundSessionMapper.class);
    private final PlaygroundTurnMapper turnMapper = mock(PlaygroundTurnMapper.class);
    private final PlaygroundTurnFinalizer finalizer = mock(PlaygroundTurnFinalizer.class);
    private final ModelResolver modelResolver = mock(ModelResolver.class);
    private final PlaygroundRateLimiter rateLimiter = mock(PlaygroundRateLimiter.class);
    private final LlmForwardService forward = mock(LlmForwardService.class);
    private final PlaygroundServiceImpl service = new PlaygroundServiceImpl(new PlaygroundAccessPolicy(new TenantAccessPolicy(tenantMapper, membershipMapper), mock(PlaygroundWorkspaceMapper.class)),
                tenantMapper,
                membershipMapper,
                serviceMapper,
                capabilityMapper,
                sessionMapper,
                turnMapper,
                finalizer,
                modelResolver,
                rateLimiter,
                forward);
    private final UserContext actor = new UserContext().setUserId(7L).setCurrentTenantId(2L);
    private final TenantDO tenant = new TenantDO();

    @BeforeEach
    void setUp() {
        tenant.setTenantId(2L);
        tenant.setPlaygroundEnabled(1);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(membershipMapper.selectOne(any())).thenReturn(new UserTenantDO());
    }

    @Test
    void stoppedAndFailedTurnsAreVisibleButNeverSentAsContext() throws Exception {
        givenOwnedSession();
        when(turnMapper.selectList(any())).thenReturn(List.of(
                turn("COMPLETED", "first", "answer"),
                turn("STOPPED", "stopped", "partial"),
                turn("FAILED", "failed", null)));
        AtomicBoolean upstreamSubscribed = new AtomicBoolean(false);
        when(forward.playgroundChatStream(any(), eq(9L), anyList(), anyString(), any()))
                .thenReturn(Flux.just(
                                ServerSentEvent.<String>builder(
                                        "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}").build(),
                                ServerSentEvent.<String>builder(
                                        "{\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}").build(),
                                ServerSentEvent.<String>builder("[DONE]").build())
                        .doOnSubscribe(ignored -> upstreamSubscribed.set(true)));

        var prepared = service.prepareSend(actor, 2L, 11L, "new question", UUID.randomUUID().toString());
        var events = prepared.events().collectList().block();
        assertTrue(upstreamSubscribed.get(), "SSE 订阅必须真正启动模型流");
        assertEquals(List.of("accepted", "delta", "completed"),
                events.stream().map(ServerSentEvent::event).toList());
        ArgumentCaptor<PlaygroundTurnDO> turn = ArgumentCaptor.forClass(PlaygroundTurnDO.class);
        verify(turnMapper).insert(turn.capture());
        String turnId = turn.getValue().getTurnId().toString();
        JSONAssert.assertEquals("""
                {"sessionId":"11","turnId":"%s","requestId":"%s","status":"PENDING"}
                """.formatted(turnId, prepared.requestId()), events.getFirst().data(), true);
        JSONAssert.assertEquals("""
                {"turnId":"%s","seq":1,"text":"answer"}
                """.formatted(turnId), events.get(1).data(), true);
        JSONAssert.assertEquals("""
                {"turnId":"%s","status":"COMPLETED","reply":"answer"}
                """.formatted(turnId), events.getLast().data(), true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(forward).playgroundChatStream(any(), eq(9L), messages.capture(), anyString(), any());
        assertEquals(List.of(new ChatMessage("user", "first"),
                new ChatMessage("assistant", "answer"), new ChatMessage("user", "new question")),
                messages.getValue());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"choices\":[{\"delta\":{\"content\":null}}]}|UNSUPPORTED_RESPONSE",
            "{\"choices\":[{\"delta\":{\"tool_calls\":null}}]}|UNSUPPORTED_RESPONSE",
            "{\"error\":null}|UPSTREAM_ERROR",
            "{\"choices\":[|UPSTREAM_ERROR"
    })
    void rejectedFramesEndTurnWithTheCompatibleErrorEvent(String frame, PlaygroundErrorCodeEnum code)
            throws Exception {
        givenOwnedSession();
        when(forward.playgroundChatStream(any(), eq(9L), anyList(), anyString(), any()))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder(frame).build()));

        var prepared = service.prepareSend(actor, 2L, 11L, "new question", UUID.randomUUID().toString());
        var events = prepared.events().collectList().block();
        assertEquals(List.of("accepted", "error"), events.stream().map(ServerSentEvent::event).toList());
        ArgumentCaptor<PlaygroundTurnDO> turn = ArgumentCaptor.forClass(PlaygroundTurnDO.class);
        verify(turnMapper).insert(turn.capture());
        Long turnId = turn.getValue().getTurnId();
        verify(finalizer).finish(2L, 7L, 11L, turnId, "", "FAILED");
        JSONAssert.assertEquals("""
                {"turnId":"%s","status":"FAILED","code":"%s","message":"%s"}
                """.formatted(turnId, code.code(), code.message()), events.getLast().data(), true);
    }

    @ParameterizedTest
    @EnumSource(value = CostBudgetErrorCodeEnum.class, names = {"LIMIT_EXCEEDED", "UNAVAILABLE"})
    void lateBudgetFailurePreservesPartialReplyAndOnlySendsSafeRecoveryDetails(CostBudgetErrorCodeEnum code)
            throws Exception {
        givenOwnedSession();
        String retryAt = "2026-11-08T00:00:00+08:00";
        AbstractException failure = code == CostBudgetErrorCodeEnum.LIMIT_EXCEEDED
                ? new CostLimitExceededException(new CostBudgetDecision(
                        List.of(new Hit("TENANT", "DAY", retryAt)), false))
                : new CostBudgetUnavailableException();
        ServerSentEvent<String> delta = ServerSentEvent.<String>builder(
                "{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}").build();
        when(forward.playgroundChatStream(any(), eq(9L), anyList(), anyString(), any()))
                .thenReturn(Flux.just(delta).concatWith(Flux.error(failure)));

        var prepared = service.prepareSend(actor, 2L, 11L, "new question", UUID.randomUUID().toString());
        var events = prepared.events().collectList().block();

        assertEquals(List.of("accepted", "delta", "error"), events.stream().map(ServerSentEvent::event).toList());
        ArgumentCaptor<PlaygroundTurnDO> turn = ArgumentCaptor.forClass(PlaygroundTurnDO.class);
        verify(turnMapper).insert(turn.capture());
        Long turnId = turn.getValue().getTurnId();
        verify(finalizer).finish(2L, 7L, 11L, turnId, "partial", "FAILED");
        String data = code == CostBudgetErrorCodeEnum.LIMIT_EXCEEDED ? """
                ,"data":{"scope":"TENANT","period":"DAY","retryAt":"%s","timezone":"Asia/Shanghai",
                "limits":[{"scope":"TENANT","period":"DAY","periodEnd":"%s"}],"requestId":"%s"}
                """.formatted(retryAt, retryAt, prepared.requestId()) : "";
        JSONAssert.assertEquals("""
                {"turnId":"%s","status":"FAILED","code":"%s","message":"%s"%s}
                """.formatted(turnId, code.code(), failure.getErrorMessage(), data), events.getLast().data(), true);
    }

    private void givenOwnedSession() {
        PlaygroundSessionDO session = new PlaygroundSessionDO();
        session.setSessionId(11L);
        session.setTenantId(2L);
        session.setOwnerUserId(7L);
        session.setServiceId(9L);
        session.setTurnCount(3);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.claim(2L, 7L, 11L)).thenReturn(1);
        LlmServiceDO model = new LlmServiceDO();
        model.setServiceId(9L);
        model.setName("model");
        when(serviceMapper.selectOne(any())).thenReturn(model);
        when(capabilityMapper.selectOne(any())).thenReturn(new LlmServiceCapabilityDO());
    }

    @Test
    void missingOwnedSessionDoesNotCallUpstream() {
        when(sessionMapper.selectOne(any())).thenReturn(null);
        ClientException error = assertThrows(ClientException.class,
                () -> service.detail(actor, 2L, 999L));
        assertEquals("A001001", error.getErrorCode());
        verifyNoInteractions(forward);
    }

    @Test
    void closedTenantStillExposesStatusButBlocksHistory() {
        tenant.setPlaygroundEnabled(0);
        assertFalse(service.status(actor, 2L).enabled());
        ClientException error = assertThrows(ClientException.class,
                () -> service.detail(actor, 2L, 11L));
        assertEquals("A001000", error.getErrorCode());
    }

    @Test
    void starterPromptsAreOrderedAndRespectTenantAccess() {
        var prompts = service.prompts(actor, 2L).items();
        assertEquals(List.of("intro-capabilities", "explain-concept", "organize-requirements", "rewrite-copy"),
                prompts.stream().map(prompt -> prompt.id()).toList());
        assertTrue(prompts.stream().allMatch(prompt -> !prompt.prompt().isBlank()));

        tenant.setPlaygroundEnabled(0);
        ClientException disabled = assertThrows(ClientException.class,
                () -> service.prompts(actor, 2L));
        assertEquals("A001000", disabled.getErrorCode());

        tenant.setPlaygroundEnabled(1);
        ClientException mismatch = assertThrows(ClientException.class,
                () -> service.prompts(actor, 3L));
        assertEquals("B000338", mismatch.getErrorCode());

        when(membershipMapper.selectOne(any())).thenReturn(null);
        ClientException left = assertThrows(ClientException.class,
                () -> service.prompts(actor, 2L));
        assertEquals("B000308", left.getErrorCode());
    }

    @Test
    void currentTenantAndActiveMembershipAreCheckedOnEveryRead() {
        ClientException mismatch = assertThrows(ClientException.class,
                () -> service.status(actor, 3L));
        assertEquals("B000338", mismatch.getErrorCode());
        verifyNoInteractions(tenantMapper);

        when(membershipMapper.selectOne(any())).thenReturn(null);
        ClientException left = assertThrows(ClientException.class,
                () -> service.sessions(actor, 2L, 1, 20, null));
        assertEquals("B000308", left.getErrorCode());
        verifyNoInteractions(sessionMapper);

        when(membershipMapper.selectOne(any())).thenReturn(new UserTenantDO());
        assertTrue(service.status(actor, 2L).enabled());
    }

    @Test
    void repeatedIdempotencyKeyNeverStartsAnotherInvocation() {
        PlaygroundTurnDO previous = turn("STOPPED", "old", "partial");
        previous.setTurnId(501L);
        when(turnMapper.selectOne(any())).thenReturn(previous);
        var duplicate = assertThrows(PlaygroundServiceImpl.DuplicateSendException.class,
                () -> service.prepareSend(actor, 2L, 11L, "again", UUID.randomUUID().toString()));
        assertEquals(501L, duplicate.original().getTurnId());
        verifyNoInteractions(forward, rateLimiter);
    }

    @Test void synchronousBudgetRefusalPersistsFailureAndReleasesGeneratingLock() {
        var session=new PlaygroundSessionDO(); session.setSessionId(11L); session.setTenantId(2L); session.setOwnerUserId(7L); session.setServiceId(9L); session.setTurnCount(0);
        when(sessionMapper.selectOne(any())).thenReturn(session); when(sessionMapper.claim(2L,7L,11L)).thenReturn(1); when(sessionMapper.release(2L,7L,11L)).thenReturn(1);
        var model=new LlmServiceDO(); model.setServiceId(9L); model.setName("model"); when(serviceMapper.selectOne(any())).thenReturn(model);
        when(capabilityMapper.selectOne(any())).thenReturn(new LlmServiceCapabilityDO()); when(turnMapper.selectList(any())).thenReturn(List.of());
        var budget=new com.yonagi.verse.service.budget.CostLimitExceededException(new com.yonagi.verse.service.budget.CostBudgetDecision(
                List.of(new com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit("LLM","DAY","2026-10-12T00:00:00+08:00")),false));
        when(forward.playgroundChatStream(any(),eq(9L),anyList(),anyString(),any())).thenThrow(budget);
        assertSame(budget,assertThrows(com.yonagi.verse.service.budget.CostLimitExceededException.class,()->service.prepareSend(actor,2L,11L,"keep this prompt",UUID.randomUUID().toString())));
        var saved=ArgumentCaptor.forClass(PlaygroundTurnDO.class); verify(turnMapper).insert(saved.capture());
        assertEquals("FAILED",saved.getValue().getStatus()); assertEquals("keep this prompt",saved.getValue().getPrompt());
        assertEquals(saved.getValue().getRequestId(),budget.getRequestId()); verify(sessionMapper).release(2L,7L,11L); verifyNoInteractions(finalizer);
    }

    private PlaygroundTurnDO turn(String status, String prompt, String reply) {
        PlaygroundTurnDO turn = new PlaygroundTurnDO();
        turn.setStatus(status);
        turn.setPrompt(prompt);
        turn.setReply(reply);
        return turn;
    }
}
