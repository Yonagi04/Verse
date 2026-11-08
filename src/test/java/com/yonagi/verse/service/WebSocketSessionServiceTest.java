package com.yonagi.verse.service;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class WebSocketSessionServiceTest {
    private final LoginSessionValidationService validation = mock(LoginSessionValidationService.class);
    private final WebSocketSessionService sessions = new WebSocketSessionService(validation);
    private final LoginSessionValidationService.Identity alice = identity(10L, "alice-token-hash");
    private final LoginSessionValidationService.Identity bob = identity(20L, "bob-token-hash");

    @Test
    void authorizedSubscriptionsRemainBoundToOriginalUserUntilTransportEnds() {
        when(validation.authenticate("alice-token")).thenReturn(alice);
        when(validation.authenticate("bob-token")).thenReturn(bob);
        assertSame(alice, sessions.connect("socket-1", "alice-token"));
        sessions.authorizeInbound("socket-1", StompCommand.SUBSCRIBE, "/user/queue/notifications");
        sessions.authorizeInbound("socket-1", StompCommand.SUBSCRIBE, "/user/queue/notifications/unread-count");
        sessions.authorizeInbound("socket-1", StompCommand.UNSUBSCRIBE, null);
        sessions.authorizeInbound("socket-1", null, null);
        assertThrows(ClientException.class, () -> sessions.connect("socket-1", "bob-token"));

        sessions.authorizeInbound("socket-1", StompCommand.DISCONNECT, null);
        assertThrows(ClientException.class, () -> sessions.requireActive("socket-1"));
        assertThrows(ClientException.class, () -> sessions.connect("socket-1", "bob-token"));
        sessions.disconnected("socket-1");
        sessions.disconnected("socket-1");
        assertThrows(ClientException.class, () -> sessions.requireActive("socket-1"));
        assertSame(bob, sessions.connect("socket-1", "bob-token"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/queue/notifications", "/topic/notifications", "/user/20/queue/notifications",
            "/user/queue/notifications-extra", "/user/queue/notifications/", "/app/notifications"})
    void unknownOrForeignDestinationsCannotSubscribe(String destination) {
        connect();
        assertThrows(ClientException.class,
                () -> sessions.authorizeInbound("socket-1", StompCommand.SUBSCRIBE, destination));
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP", "SUBSCRIBE", "UNSUBSCRIBE", "DISCONNECT"},
            mode = EnumSource.Mode.EXCLUDE)
    void clientSendAndUnknownCommandsAreDenied(StompCommand command) {
        connect();
        assertThrows(ClientException.class,
                () -> sessions.authorizeInbound("socket-1", command, "/user/queue/notifications"));
    }

    @Test
    void revokedAndExpiredSessionsCannotContinueUsingExistingSubscriptions() {
        connect();
        sessions.authorizeInbound("socket-1", StompCommand.SUBSCRIBE, "/user/queue/notifications");
        doThrow(new ClientException(BaseErrorCode.TOKEN_INVALID)).when(validation).requireActive(alice);
        assertThrows(ClientException.class, () -> sessions.requireActive("socket-1"));
        assertThrows(ClientException.class,
                () -> sessions.authorizeInbound("socket-1", StompCommand.SUBSCRIBE, "/user/queue/notifications"));
        assertThrows(ClientException.class, () -> sessions.connect("socket-1", "bob-token"));
        doThrow(new ClientException(BaseErrorCode.TOKEN_EXPIRED)).when(validation).requireActive(alice);
        assertEquals(BaseErrorCode.TOKEN_EXPIRED.code(),
                assertThrows(ClientException.class, () -> sessions.requireActive("socket-1")).getErrorCode());
    }

    @Test
    void unregisteredConnectionCannotReceiveMessagesAndDependencyFailureDoesNotGrantAccess() {
        assertThrows(ClientException.class, () -> sessions.requireActive(null));
        assertThrows(ClientException.class, () -> sessions.requireActive("unknown"));
        assertThrows(ClientException.class, () -> sessions.connect(null, "alice-token"));
        connect();
        IllegalStateException unavailable = new IllegalStateException("verification unavailable");
        doThrow(unavailable).when(validation).requireActive(alice);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> sessions.requireActive("socket-1")));
    }

    @Test
    void failedAuthenticationDoesNotClaimConnection() {
        when(validation.authenticate("bad-token")).thenThrow(new ClientException(BaseErrorCode.TOKEN_INVALID));
        assertThrows(ClientException.class, () -> sessions.connect("socket-1", "bad-token"));
        assertThrows(ClientException.class, () -> sessions.requireActive("socket-1"));
        connect();
        sessions.authorizeInbound(null, StompCommand.DISCONNECT, null);
        sessions.disconnected(null);
        assertThrows(ClientException.class, () -> sessions.connect("socket-1", "bad-token"));
    }

    @Test
    void simultaneousAuthenticationCanOnlyBindOneIdentityToATransport() throws Exception {
        CyclicBarrier bothCredentialsVerified = new CyclicBarrier(2);
        when(validation.authenticate("alice-token")).thenAnswer(invocation -> {
            bothCredentialsVerified.await(3, TimeUnit.SECONDS);
            return alice;
        });
        try (ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new CustomizableThreadFactory("ws-session-test-"),
                new ThreadPoolExecutor.AbortPolicy())) {
            Future<Boolean> first = executor.submit(this::tryConnect);
            Future<Boolean> second = executor.submit(this::tryConnect);
            List<Boolean> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
            assertThrows(ClientException.class, () -> sessions.connect("socket-1", "alice-token"));
        }
    }

    private boolean tryConnect() {
        try {
            assertSame(alice, sessions.connect("socket-1", "alice-token"));
            return true;
        } catch (ClientException denied) {
            assertEquals(BaseErrorCode.TOKEN_INVALID.code(), denied.getErrorCode());
            return false;
        }
    }

    private void connect() {
        when(validation.authenticate("alice-token")).thenReturn(alice);
        assertSame(alice, sessions.connect("socket-1", "alice-token"));
    }

    private static LoginSessionValidationService.Identity identity(Long userId, String hash) {
        return new LoginSessionValidationService.Identity(userId, "user-" + userId, "session-" + userId, hash,
                System.currentTimeMillis() + 60_000);
    }
}
