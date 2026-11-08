package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.dto.resp.LoginSessionVO;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.yonagi.verse.dao.entity.LoginDeviceDO;
import com.yonagi.verse.dao.mapper.LoginDeviceMapper;
import com.yonagi.verse.dto.resp.LoginDeviceRespDTO;
import com.yonagi.verse.service.UserLoginSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LoginDeviceServiceImplTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserLoginSessionService sessions = mock(UserLoginSessionService.class);
    private final LoginDeviceMapper mapper = mock(LoginDeviceMapper.class);
    private final LoginDeviceServiceImpl devices = new LoginDeviceServiceImpl(redis, sessions);

    @BeforeAll
    static void lambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "device-service"),
                LoginDeviceDO.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentDeviceFlagUsesSessionIdentityDespiteDifferentRequestMetadata() {
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(any())).thenReturn(Map.of("original", "session"));
        when(sessions.currentDeviceId(10L)).thenReturn(Optional.of("original"));
        ReflectionTestUtils.setField(devices, "baseMapper", mapper);
        when(mapper.selectList(any())).thenReturn(List.of(
                LoginDeviceDO.builder().deviceId("original").build(),
                LoginDeviceDO.builder().deviceId("another").build()));
        MockHttpServletRequest changedRequest = new MockHttpServletRequest();
        changedRequest.setRemoteAddr("203.0.113.10");
        changedRequest.addHeader("User-Agent", "new-agent");

        List<LoginDeviceRespDTO> result = devices.listDevices(10L, changedRequest);

        assertEquals(2, result.size());
        assertTrue(result.getFirst().getCurrentDevice());
        assertTrue(result.getFirst().getOnline());
        assertFalse(result.getLast().getCurrentDevice());
        assertFalse(result.getLast().getOnline());
    }

    @Test
    void cannotKickAuthenticatedDeviceAfterUserAgentOrNetworkChanges() {
        when(sessions.currentDeviceId(10L)).thenReturn(Optional.of("original"));
        ClientException denied = assertThrows(ClientException.class,
                () -> devices.kickDevice(10L, "original", new MockHttpServletRequest()));
        assertEquals(UserErrorCodeEnum.CANNOT_KICK_CURRENT_DEVICE.code(), denied.getErrorCode());
        verifyNoInteractions(redis, mapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherDeviceCanStillBeRevokedWithoutRemovingCurrentCredential() {
        when(sessions.currentDeviceId(10L)).thenReturn(Optional.of("original"));
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.get(RedisKeyConstant.USER_DEVICES_KEY + 10L, "another"))
                .thenReturn(JSON.toJSONString(LoginSessionVO.builder().userId(10L).token("another-token").build()));
        ReflectionTestUtils.setField(devices, "baseMapper", mapper);

        assertTrue(devices.kickDevice(10L, "another", new MockHttpServletRequest()));

        verify(redis).delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex("another-token"));
        verify(hashes).delete(RedisKeyConstant.USER_DEVICES_KEY + 10L, "another");
    }
}
