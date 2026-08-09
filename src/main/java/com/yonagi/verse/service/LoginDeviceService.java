package com.yonagi.verse.service;

import com.yonagi.verse.dto.resp.LoginDeviceRespDTO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 15:04
 */
public interface LoginDeviceService {

    List<LoginDeviceRespDTO> listDevices(Long userId, HttpServletRequest request);

    Boolean kickDevice(Long userId, String deviceId, HttpServletRequest request);

    void upsertLoginDevice(Long userId, String deviceId, String deviceName, String ip, String region);

    void logoutDevice(Long userId, String deviceId);

    void logoutAllDevice(Long userId);
}
