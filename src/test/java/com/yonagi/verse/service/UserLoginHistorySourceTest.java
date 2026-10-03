package com.yonagi.verse.service;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.event.LoginLogEvent;
import com.yonagi.verse.async.handler.LoginLogEventHandler;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.security.UserSecurityLocks;
import com.yonagi.verse.common.util.GeoIpUtil;
import com.yonagi.verse.dao.entity.LoginHistoryDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.LoginHistoryMapper;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserLoginHistorySourceTest {

    @ParameterizedTest
    @ValueSource(strings = {"FEISHU", "PASSWORD", "GOOGLE", "GITHUB", "GITLAB"})
    void loginSourceSurvivesSessionEventSerializationAndPersistence(String source) {
        var jwt = mock(JwtUtil.class);
        var redis = mock(StringRedisTemplate.class);
        var events = mock(DomainEventPublisher.class);
        var locks = mock(UserSecurityLocks.class);
        var geoIp = mock(GeoIpUtil.class);
        var claims = mock(Claims.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(jwt.generateToken(10L, "alice")).thenReturn("token");
        when(jwt.parseToken("token")).thenReturn(claims);
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(claims.getId()).thenReturn("session-id");
        when(locks.withUser(eq(10L), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        var sessions = new UserLoginSessionService(jwt, redis, mock(CurrentTenantStateService.class), geoIp, events, locks);
        var user = new UserDO();
        user.setUserId(10L); user.setUsername("alice"); user.setStatus(1);
        var request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Mozilla/5.0");

        sessions.create(user, source, request, new CurrentTenantState());

        var eventCaptor = ArgumentCaptor.forClass(LoginLogEvent.class);
        verify(events).publishInTx(eventCaptor.capture());
        // 模拟消息序列化与消费，确认实际传给历史表的来源没有回退为密码。
        var event = JSON.parseObject(JSON.toJSONString(eventCaptor.getValue()), LoginLogEvent.class);
        assertEquals(source, event.getLoginSource());
        var history = persist(event);
        assertEquals(source, history.getLoginSource());
        assertEquals(10L, history.getUserId());
        assertEquals("成功", history.getResult());
        assertEquals(event.getLoginTime(), history.getLoginTime());
    }

    @Test
    void legacyEventWithoutSourceStillUsesPassword() {
        var event = new LoginLogEvent();
        event.setUserId(10L); event.setResult("失败");
        assertEquals("PASSWORD", persist(event).getLoginSource());
    }

    private LoginHistoryDO persist(LoginLogEvent event) {
        var mapper = mock(LoginHistoryMapper.class);
        var handler = new LoginLogEventHandler(mapper, mock(LoginDeviceService.class), mock(StringRedisTemplate.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            handler.onEvent(event);
            var captor = ArgumentCaptor.forClass(LoginHistoryDO.class);
            verify(mapper).insert(captor.capture());
            return captor.getValue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
