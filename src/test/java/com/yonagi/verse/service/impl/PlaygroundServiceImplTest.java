package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.LlmServiceCapabilityDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.PlaygroundSessionDO;
import com.yonagi.verse.dao.entity.PlaygroundTurnDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.forward.ChatMessage;
import com.yonagi.verse.service.forward.ModelResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    private final PlaygroundServiceImpl service = new PlaygroundServiceImpl(tenantMapper,
            membershipMapper, serviceMapper, capabilityMapper, sessionMapper, turnMapper, finalizer,
            modelResolver, rateLimiter, forward);
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
    void stoppedAndFailedTurnsAreVisibleButNeverSentAsContext() {
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
        when(turnMapper.selectList(any())).thenReturn(List.of(
                turn("COMPLETED", "first", "answer"),
                turn("STOPPED", "stopped", "partial"),
                turn("FAILED", "failed", null)));
        AtomicBoolean upstreamSubscribed = new AtomicBoolean(false);
        when(forward.playgroundChatStream(any(), eq(9L), anyList(), anyString(), any()))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder(
                                "{\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}").build())
                        .doOnSubscribe(ignored -> upstreamSubscribed.set(true)));

        var prepared = service.prepareSend(actor, 2L, 11L, "new question", UUID.randomUUID().toString());
        var events = prepared.events().collectList().block();
        assertTrue(upstreamSubscribed.get(), "SSE 订阅必须真正启动模型流");
        assertEquals(List.of("accepted", "delta", "completed"),
                events.stream().map(ServerSentEvent::event).toList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(forward).playgroundChatStream(any(), eq(9L), messages.capture(), anyString(), any());
        assertEquals(List.of(new ChatMessage("user", "first"),
                new ChatMessage("assistant", "answer"), new ChatMessage("user", "new question")),
                messages.getValue());
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

    private PlaygroundTurnDO turn(String status, String prompt, String reply) {
        PlaygroundTurnDO turn = new PlaygroundTurnDO();
        turn.setStatus(status);
        turn.setPrompt(prompt);
        turn.setReply(reply);
        return turn;
    }
}
