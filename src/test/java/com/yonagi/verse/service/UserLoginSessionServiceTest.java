package com.yonagi.verse.service;

import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.security.UserSecurityLocks;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.dto.resp.LoginSessionVO;
import com.yonagi.verse.dto.resp.UserLoginRespDTO;
import com.yonagi.verse.service.impl.UserServiceImpl;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.util.GeoIpUtil;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLoginSessionServiceTest {
    @Mock JwtUtil jwt;
    @Mock StringRedisTemplate redis;
    @Mock CurrentTenantStateService tenants;
    @Mock GeoIpUtil geoIp;
    @Mock DomainEventPublisher events;
    @Mock UserSecurityLocks locks;
    @Mock UserMapper users;
    @Mock HashOperations<String, Object, Object> hashes;
    @Mock Claims claims;
    @Mock ValueOperations<String, String> tokens;
    @Mock LoginDeviceService deviceProjection;
    @InjectMocks UserLoginSessionService sessions;
    @InjectMocks UserServiceImpl userService;

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @BeforeEach
    void validSessionDependencies() {
        request.addHeader("User-Agent", "Mozilla/5.0");
        lenient().when(locks.withUser(eq(10L), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        lenient().when(redis.opsForHash()).thenReturn(hashes);
        lenient().when(jwt.generateToken(10L, "alice")).thenReturn("token");
        lenient().when(jwt.parseToken("token")).thenReturn(claims);
        lenient().when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        lenient().when(claims.getId()).thenReturn("session-id");
    }

    @Test
    void passwordResetAfterCredentialCheckCannotCreateSession() {
        lenient().when(users.selectActiveUserForShare(10L)).thenReturn(user("new-hash"));
        ClientException failure = assertThrows(ClientException.class,
                () -> sessions.create(user("verified-old-hash"), "PASSWORD", request, new CurrentTenantState()));
        assertEquals(UserErrorCodeEnum.PASSWORD_ERROR.code(), failure.getErrorCode());
        verifyNoInteractions(jwt, redis, events);
    }

    @Test
    void closedAccountAfterCredentialCheckCannotCreateSession() {
        lenient().when(users.selectActiveUserForShare(10L)).thenReturn(null);
        ClientException failure = assertThrows(ClientException.class,
                () -> sessions.create(user("verified-hash"), "PASSWORD", request, new CurrentTenantState()));
        assertEquals(UserErrorCodeEnum.USER_ACCOUNT_BANNED.code(), failure.getErrorCode());
        verifyNoInteractions(jwt, redis, events);
    }

    @Test
    void unchangedPasswordUsesCurrentAccountAndPreservesLoginContract() {
        UserDO current = user("verified-hash");
        current.setNickname("current nickname");
        when(users.selectActiveUserForShare(10L)).thenReturn(current);
        when(tenants.resolveCurrentTenant(10L)).thenReturn(new CurrentTenantState().setTenantId(20L)
                .setName("team").setType("TEAM").setRole("MEMBER"));
        UserLoginRespDTO result = sessions.create(user("verified-hash"), "PASSWORD", request);
        assertEquals(10L, result.getUserId());
        assertEquals("alice", result.getUsername());
        assertEquals("current nickname", result.getNickname());
        assertEquals("token", result.getToken());
        assertEquals(claims.getExpiration(), result.getExpiresAt());
        assertEquals(20L, result.getCurrentTenant().getTenantId());
    }

    @Test
    void externalIdentityDoesNotRequirePasswordSnapshotButStillChecksAccount() {
        when(users.selectActiveUserForShare(10L)).thenReturn(user("new-hash"));
        UserLoginRespDTO result = sessions.create(user(null), "GITHUB", request, new CurrentTenantState());
        assertEquals(10L, result.getUserId());
        assertEquals("token", result.getToken());
        assertNull(result.getCurrentTenant());
        when(users.selectActiveUserForShare(10L)).thenReturn(null);
        assertEquals(UserErrorCodeEnum.USER_ACCOUNT_BANNED.code(), assertThrows(ClientException.class,
                () -> sessions.create(user(null), "GITHUB", request, new CurrentTenantState())).getErrorCode());
    }

    @Test
    void disabledOrDeletedCurrentAccountAndMissingPasswordSnapshotAreRejected() {
        UserDO disabled = user("verified-hash");
        disabled.setStatus(0);
        when(users.selectActiveUserForShare(10L)).thenReturn(disabled);
        assertThrows(ClientException.class,
                () -> sessions.create(user("verified-hash"), "PASSWORD", request, new CurrentTenantState()));
        disabled.setStatus(null);
        assertThrows(ClientException.class,
                () -> sessions.create(user("verified-hash"), "PASSWORD", request, new CurrentTenantState()));
        disabled.setStatus(1);
        disabled.setDelFlag(1);
        assertThrows(ClientException.class,
                () -> sessions.create(user("verified-hash"), "PASSWORD", request, new CurrentTenantState()));
        disabled.setDelFlag(0);
        assertEquals(UserErrorCodeEnum.PASSWORD_ERROR.code(), assertThrows(ClientException.class,
                () -> sessions.create(user(null), "PASSWORD", request, new CurrentTenantState())).getErrorCode());
        verifyNoInteractions(jwt, redis, events);
    }

    @Test
    void accountReadFailureDoesNotPublishASession() {
        IllegalStateException unavailable = new IllegalStateException("account unavailable");
        when(users.selectActiveUserForShare(10L)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> sessions.create(user("hash"), "PASSWORD", request, new CurrentTenantState())));
        verifyNoInteractions(jwt, redis, events);
    }

    @Test
    void changedUaAndIpLogoutRevokesAuthenticatedTokenAndMarksItsOriginalDeviceOffline() {
        authenticatedContext();
        when(hashes.entries(RedisKeyConstant.USER_DEVICES_KEY + 10L)).thenReturn(Map.of(
                "original-device", sessionJson("token", 10L), "another-device", sessionJson("other-token", 10L)));
        request.addHeader("User-Agent", "different-agent");
        request.setRemoteAddr("203.0.113.12");
        ReflectionTestUtils.setField(userService, "loginSessions", sessions);

        assertTrue(userService.logout(10L, request));

        verify(redis).delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex("token"));
        verify(redis, never()).delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex("other-token"));
        verify(hashes).delete(RedisKeyConstant.USER_DEVICES_KEY + 10L, "original-device");
        verify(deviceProjection).logoutDevice(10L, "original-device");
    }

    @Test
    void missingDeviceProjectionCannotPreventTokenRevocation() {
        authenticatedContext();
        when(hashes.entries(RedisKeyConstant.USER_DEVICES_KEY + 10L)).thenReturn(Map.of(
                "foreign", sessionJson("token", 20L), "null", "null",
                "other-device", sessionJson("other-token", 10L),
                "no-token", JSON.toJSONString(LoginSessionVO.builder().userId(10L).build())));
        assertEquals(Optional.empty(), sessions.revokeCurrent(10L));
        verify(redis).delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex("token"));
        verify(hashes, never()).delete(any(), any());
    }

    @Test
    void projectionFailureOccursAfterCredentialRevocationAndDoesNotRestoreIt() {
        authenticatedContext();
        IllegalStateException unavailable = new IllegalStateException("device projection unavailable");
        when(hashes.entries(RedisKeyConstant.USER_DEVICES_KEY + 10L)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> sessions.revokeCurrent(10L)));
        verify(redis).delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex("token"));
    }

    @Test
    void currentDeviceLookupMatchesCredentialWithoutMutatingSessions() {
        authenticatedContext();
        when(hashes.entries(RedisKeyConstant.USER_DEVICES_KEY + 10L)).thenReturn(Map.of(
                "original-device", sessionJson("token", 10L), "another-device", sessionJson("other-token", 10L)));
        assertEquals(Optional.of("original-device"), sessions.currentDeviceId(10L));
        verify(redis, never()).delete(anyString());
        verify(hashes, never()).delete(any(), any());
    }

    @Test
    void untrustedOrForeignContextCannotRevokeAUserSession() {
        assertThrows(ClientException.class, () -> sessions.revokeCurrent(10L));
        UserContextHolder.set(new UserContext().setUserId(20L).setTokenHash("hash"));
        assertThrows(ClientException.class, () -> sessions.revokeCurrent(10L));
        UserContextHolder.set(new UserContext().setUserId(10L));
        assertThrows(ClientException.class, () -> sessions.revokeCurrent(10L));
        UserContextHolder.set(new UserContext().setUserId(10L).setTokenHash(" "));
        assertThrows(ClientException.class, () -> sessions.revokeCurrent(10L));
        verifyNoInteractions(redis);
    }

    @Test
    void mismatchedTokenIndexCannotRevokeOrLocateDevices() {
        authenticatedContext();
        when(tokens.get(any())).thenReturn("20");
        assertThrows(ClientException.class, () -> sessions.revokeCurrent(10L));
        assertThrows(ClientException.class, () -> sessions.currentDeviceId(10L));
        verify(redis, never()).delete(anyString());
        verifyNoInteractions(hashes);
    }

    private void authenticatedContext() {
        lenient().when(redis.opsForValue()).thenReturn(tokens);
        lenient().when(tokens.get(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex("token"))).thenReturn("10");
        UserContextHolder.set(new UserContext().setUserId(10L).setTokenHash(DigestUtil.md5Hex("token")));
    }

    private static String sessionJson(String token, Long userId) {
        return JSON.toJSONString(LoginSessionVO.builder().userId(userId).token(token).build());
    }

    private static UserDO user(String hash) {
        UserDO user = new UserDO();
        user.setUserId(10L);
        user.setUsername("alice");
        user.setStatus(1);
        user.setDelFlag(0);
        user.setPassword(hash);
        return user;
    }
}
