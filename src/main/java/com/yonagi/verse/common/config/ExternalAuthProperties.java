package com.yonagi.verse.common.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
@Data
@Component
@ConfigurationProperties("verse.external-auth")
public class ExternalAuthProperties {
    /** 外部认证总开关 */
    private boolean enabled = false;
    /** 唯一可信前端来源 */
    private String frontendOrigin = "http://localhost:3000";
    /** 外部平台请求的连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** 外部平台请求的读取超时 */
    private Duration readTimeout = Duration.ofSeconds(15);
    /** 后端出站代理地址，支持 http、socks、socks5；空值沿用 JVM 网络设置 */
    private String proxyUrl;
    /** 授权有效期 */
    private Duration authTtl = Duration.ofMinutes(10);
    /** 授权后动作有效期 */
    private Duration actionTtl = Duration.ofMinutes(5);
    /** 近期验密有效期 */
    private Duration recentAuthTtl = Duration.ofMinutes(5);
    /** 每分钟发起次数 */
    private int startsPerMinute = 10;
    /** 五分钟验密次数 */
    private int passwordAttempts = 5;
    /** 平台应用配置 */
    private Map<String, Provider> providers = new HashMap<>();
    @Data
    public static class Provider {
        /** 平台开关 */
        private boolean enabled;
        /** 应用标识 */
        private String clientId;
        /** 应用秘密，仅后端使用 */
        private String clientSecret;
    }
}
