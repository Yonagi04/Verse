package com.yonagi.verse.common.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties("verse.messaging")
public class MessagingProperties {
    /** 网络连接超时。 */
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** 网络读取和 SMTP 写入超时。 */
    private Duration readTimeout = Duration.ofSeconds(10);
    /** 每通道进程内同时发送上限。 */
    private int maxConcurrency = 8;
    /** 短信配置。 */
    private Sms sms = new Sms();
    /** 邮件配置。 */
    private Email email = new Email();
    /** 异步执行和持久化任务边界。 */
    private Async async = new Async();

    @Data public static class Async {
        /** 每通道内存队列容量，不含运行中任务。 */
        private int queueCapacity = 32;
        /** 每通道数据库待执行积压的准入阈值，不是跨实例严格额度。 */
        private int backlogLimit = 256;
        /** 后台有界扫描间隔。 */
        private long pollIntervalMillis = 500;
        /** 从受理起计算的任务执行截止时间，不延长验证码 TTL。 */
        private Duration maxTaskAge = Duration.ofSeconds(60);
        /** 包含首次执行的最大尝试次数。 */
        private int maxAttempts = 3;
        /** 确定未外发的临时失败重试基准间隔。 */
        private Duration retryBackoff = Duration.ofSeconds(2);
        /** 超时未完成执行转 UNKNOWN 的核查窗口，绝不自动重发。 */
        private Duration abandonedAfter = Duration.ofMinutes(3);
    }

    @Data public static class Sms {
        /** disabled、aliyun 或 sms-dev。 */
        private String provider = "disabled";
        /** 业务短信签名。 */
        private String signName = "Verse";
        /** 找回密码模板代码。 */
        private String resetTemplateCode = "10001";
        /** 账号注销模板代码。 */
        private String closureTemplateCode = "10002";
        /** 每手机号首次计数起 24 小时固定窗口内的发码尝试上限，包含明确拒绝。 */
        private int dailyLimit = 10;
        /** 模拟服务 API 地址。 */
        private String devBaseUrl = "http://127.0.0.1:4001";
        /** 独立模板文件，支持 classpath: 与 file:。 */
        private String templatesLocation = "classpath:messaging/sms/templates.yml";
        /** 阿里云短信授权。 */
        private Aliyun aliyun = new Aliyun("dysmsapi.aliyuncs.com");
    }

    @Data public static class Email {
        /** disabled、aliyun 或 mailpit。 */
        private String provider = "disabled";
        /** 阿里云审核通过的发信地址，模拟环境使用开发地址。 */
        private String accountName;
        /** 发信显示名称，保持阿里云长度限制。 */
        private String fromAlias = "Verse";
        /** 是否使用阿里云已验证回信地址。 */
        private boolean replyToAddress = false;
        /** Mailpit SMTP 主机。 */
        private String smtpHost = "127.0.0.1";
        /** Mailpit SMTP 端口。 */
        private int smtpPort = 1025;
        /** 阿里云邮件推送授权。 */
        private Aliyun aliyun = new Aliyun("dm.aliyuncs.com");
    }

    @Data public static class Aliyun {
        /** 业务地域对应的 API 域名。 */
        private String endpoint;
        /** API 访问标识。 */
        @ToString.Exclude private String accessKeyId;
        /** API 密钥。 */
        @ToString.Exclude private String accessKeySecret;
        /** 临时凭据令牌，可为空。 */
        @ToString.Exclude private String securityToken;
        public Aliyun(String endpoint) { this.endpoint = endpoint; }
    }

    public void validateCommon() {
        if (connectTimeout == null || connectTimeout.toMillis() < 1 || connectTimeout.toMillis() > 30000
                || readTimeout == null || readTimeout.toMillis() < 1 || readTimeout.toMillis() > 30000
                || maxConcurrency < 1 || maxConcurrency > 100 || sms.getDailyLimit() < 1 || sms.getDailyLimit() > 100) {
            throw new IllegalStateException("Invalid messaging timeouts, concurrency or daily limit");
        }
        if (async == null || async.getQueueCapacity() < 0 || async.getQueueCapacity() > 1000
                || async.getBacklogLimit() < 1 || async.getBacklogLimit() > 10000
                || async.getPollIntervalMillis() < 100 || async.getPollIntervalMillis() > 5000
                || async.getMaxAttempts() < 1 || async.getMaxAttempts() > 5
                || async.getMaxTaskAge() == null || async.getMaxTaskAge().toMillis() < 1000 || async.getMaxTaskAge().toMillis() > 300000
                || async.getRetryBackoff() == null || async.getRetryBackoff().toMillis() < 100 || async.getRetryBackoff().toMillis() > 30000
                || async.getAbandonedAfter() == null || async.getAbandonedAfter().toMillis() < 120000
                || async.getAbandonedAfter().toMillis() > 600000) {
            throw new IllegalStateException("Invalid messaging async bounds");
        }
    }
}
