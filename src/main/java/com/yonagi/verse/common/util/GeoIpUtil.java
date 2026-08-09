package com.yonagi.verse.common.util;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 10:21
 */
@Component
@Slf4j
public class GeoIpUtil {

    @Value("${verse.geoip.db-path:classpath:geoip/GeoLite2-City.mmdb}")
    private Resource geoIpDb;

    @Value("${verse.geoip.enabled:true}")
    private boolean enabled;

    private DatabaseReader reader;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("GeoIP is disabled. Region will always be '未知'.");
            return;
        }
        try {
            reader = new DatabaseReader.Builder(geoIpDb.getInputStream()).build();
            log.info("GeoIP database loaded successfully.");
        } catch (Exception e) {
            log.warn("Failed to load GeoIP database. Region will always be '未知'.", e);
        }
    }

    public String lookupRegion(String ip) {
        if (!enabled || reader == null) {
            return "未知";
        }
        try {
            // 过滤内网 IP
            if (isPrivateIp(ip)) {
                return "本地";
            }
            CityResponse response = reader.city(InetAddress.getByName(ip));
            String city = response.getCity().getName();
            return city != null ? city : "未知";
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for IP: {}", ip);
            return "未知";
        }
    }

    private boolean isPrivateIp(String ip) {
        return ip.startsWith("127.") || ip.startsWith("10.")
                || ip.startsWith("192.168.") || ip.startsWith("172.");
    }
}
