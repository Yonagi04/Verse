package com.yonagi.verse.common.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 10:13
 */
@Slf4j
public class DeviceUtil {

    public static String parseDeviceName(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return "未知设备";
        }
        try {
            UserAgent ua = UserAgentUtil.parse(userAgent);
            String os = ua.getOs() != null ? ua.getOs().getName() : "Unknown";
            String browser = ua.getBrowser() != null ? ua.getBrowser().getName() : "Unknown";
            return os + " " + browser;
        } catch (Exception e) {
            log.warn("Failed to parse User-Agent: {}", userAgent, e);
            return "未知设备";
        }
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    public static String generateDeviceId(String userAgent, String ip) {
        String ipPrefix = ip.contains(".") ? ip.substring(0, ip.lastIndexOf('.')) : ip;
        return DigestUtil.sha256Hex(userAgent + "/" + ipPrefix);
    }
}
