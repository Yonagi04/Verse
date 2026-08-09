package com.yonagi.verse.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.util.DeviceUtil;
import com.yonagi.verse.dao.entity.LoginDeviceDO;
import com.yonagi.verse.dao.mapper.LoginDeviceMapper;
import com.yonagi.verse.dto.resp.LoginDeviceRespDTO;
import com.yonagi.verse.dto.resp.LoginSessionVO;
import com.yonagi.verse.service.LoginDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 15:05
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginDeviceServiceImpl extends ServiceImpl<LoginDeviceMapper, LoginDeviceDO> implements LoginDeviceService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<LoginDeviceRespDTO> listDevices(Long userId, HttpServletRequest request) {
        String hashKey = RedisKeyConstant.USER_DEVICES_KEY + userId;
        Map<Object, Object> onlineDevices = stringRedisTemplate.opsForHash().entries(hashKey);

        String userAgent = request.getHeader("User-Agent");
        String ip = DeviceUtil.getClientIp(request);
        String currentDeviceId = DeviceUtil.generateDeviceId(userAgent, ip);

        List<LoginDeviceDO> allDevices = baseMapper.selectList(Wrappers.lambdaQuery(LoginDeviceDO.class)
                .eq(LoginDeviceDO::getUserId, userId)
                .orderByDesc(LoginDeviceDO::getLastLoginAt));
        return allDevices.stream().map(device -> LoginDeviceRespDTO.builder()
                .deviceId(device.getDeviceId())
                .deviceName(device.getDeviceName())
                .region(device.getRegion())
                .ip(device.getIp())
                .lastLoginAt(device.getLastLoginAt())
                .online(onlineDevices.containsKey(device.getDeviceId()))
                .currentDevice(device.getDeviceId().equals(currentDeviceId))
                .build()).collect(Collectors.toList());
    }

    @Override
    public Boolean kickDevice(Long userId, String deviceId, HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        String ip = DeviceUtil.getClientIp(request);
        String currentDeviceId = DeviceUtil.generateDeviceId(userAgent, ip);
        if (currentDeviceId.equals(deviceId)) {
            throw new ClientException(UserErrorCodeEnum.CANNOT_KICK_CURRENT_DEVICE);
        }

        String hashKey = RedisKeyConstant.USER_DEVICES_KEY + userId;
        Object sessionJson = stringRedisTemplate.opsForHash().get(hashKey, deviceId);
        if (sessionJson != null) {
            LoginSessionVO session = JSON.parseObject(sessionJson.toString(), LoginSessionVO.class);
            String tokenHash = DigestUtil.md5Hex(session.getToken());
            stringRedisTemplate.delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash);
        }
        stringRedisTemplate.opsForHash().delete(hashKey, deviceId);
        baseMapper.update(Wrappers.lambdaUpdate(LoginDeviceDO.class)
                .eq(LoginDeviceDO::getUserId, userId)
                .eq(LoginDeviceDO::getDeviceId, deviceId)
                .set(LoginDeviceDO::getStatus, 0));

        return Boolean.TRUE;
    }
}
