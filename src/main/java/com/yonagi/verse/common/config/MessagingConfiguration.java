package com.yonagi.verse.common.config;

import com.yonagi.verse.common.enums.MessagingErrorCode;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.messaging.MessageRequestValidation;
import com.yonagi.verse.common.messaging.SmsTemplateRenderer;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.SmsProvider;
import com.yonagi.verse.common.messaging.provider.EmailProvider;
import com.yonagi.verse.common.messaging.provider.impl.SmsDevAdapter;
import com.yonagi.verse.common.messaging.provider.impl.AliyunSmsAdapter;
import com.yonagi.verse.common.messaging.provider.impl.AliyunEmailAdapter;
import com.yonagi.verse.common.messaging.provider.impl.MailpitEmailAdapter;

import com.aliyun.teaopenapi.models.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {
    @Bean public SmsProvider smsProvider(MessagingProperties p, Environment environment, ResourceLoader resources) throws Exception {
        p.validateCommon();
        productionProvider(environment, p.getSms().getProvider(), "sms-dev");
        return switch (p.getSms().getProvider()) {
            case "aliyun" -> {
                check(p.getSms().getSignName() != null && !p.getSms().getSignName().isBlank(), "SMS sign required");
                check(p.getSms().getResetTemplateCode() != null && p.getSms().getResetTemplateCode().startsWith("SMS_")
                        && p.getSms().getClosureTemplateCode() != null && p.getSms().getClosureTemplateCode().startsWith("SMS_"),
                        "Aliyun approved SMS template codes required");
                yield new AliyunSmsAdapter(new com.aliyun.dysmsapi20170525.Client(aliyunConfig(p.getSms().getAliyun())),
                        (int) p.getConnectTimeout().toMillis(), (int) p.getReadTimeout().toMillis());
            }
            case "sms-dev" -> {
                URI uri = URI.create(p.getSms().getDevBaseUrl());
                check(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                        && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, "Invalid sms-dev URL");
                String location = p.getSms().getTemplatesLocation();
                check(location != null && (location.startsWith("classpath:") || location.startsWith("file:")), "Invalid template location");
                var renderer = new SmsTemplateRenderer(resources.getResource(location));
                renderer.render(p.getSms().getSignName(), p.getSms().getResetTemplateCode(), java.util.Map.of("code", "000000"));
                renderer.render(p.getSms().getSignName(), p.getSms().getClosureTemplateCode(), java.util.Map.of("code", "000000"));
                var factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout((int) p.getConnectTimeout().toMillis());
                factory.setReadTimeout((int) p.getReadTimeout().toMillis());
                yield new SmsDevAdapter(RestClient.builder().baseUrl(uri.toString()).requestFactory(factory).build(), renderer);
            }
            case "disabled" -> new SmsProvider() {
                public String name() { return "disabled"; }
                public void validate(SmsSendReqDTO request) { throw new MessageSendException(MessagingErrorCode.DISABLED, false); }
                public MessageSubmissionRespDTO send(SmsSendReqDTO request) { throw new MessageSendException(MessagingErrorCode.DISABLED, false); }
            };
            default -> throw new IllegalStateException("Unknown SMS provider");
        };
    }

    @Bean public EmailProvider emailProvider(MessagingProperties p, Environment environment) throws Exception {
        p.validateCommon();
        var config = p.getEmail();
        productionProvider(environment, config.getProvider(), "mailpit");
        if (!"disabled".equals(config.getProvider())) {
            check(MessageRequestValidation.email(config.getAccountName()), "Valid sender account required");
            check(MessageRequestValidation.text(config.getFromAlias(), 14), "Invalid sender alias");
        }
        return switch (config.getProvider()) {
            case "aliyun" -> new AliyunEmailAdapter(new com.aliyun.dm20151123.Client(aliyunConfig(config.getAliyun())), p);
            case "mailpit" -> {
                check(config.getSmtpHost() != null && !config.getSmtpHost().isBlank()
                        && config.getSmtpPort() > 0 && config.getSmtpPort() <= 65535, "Invalid Mailpit address");
                var sender = new JavaMailSenderImpl();
                sender.setHost(config.getSmtpHost()); sender.setPort(config.getSmtpPort()); sender.setDefaultEncoding("UTF-8");
                var mail = sender.getJavaMailProperties();
                mail.put("mail.smtp.connectiontimeout", Long.toString(p.getConnectTimeout().toMillis()));
                mail.put("mail.smtp.timeout", Long.toString(p.getReadTimeout().toMillis()));
                mail.put("mail.smtp.writetimeout", Long.toString(p.getReadTimeout().toMillis()));
                yield new MailpitEmailAdapter(sender, config);
            }
            case "disabled" -> new EmailProvider() {
                public String name() { return "disabled"; }
                public void validate(EmailSendReqDTO request) { throw new MessageSendException(MessagingErrorCode.DISABLED, false); }
                public MessageSubmissionRespDTO send(EmailSendReqDTO request) { throw new MessageSendException(MessagingErrorCode.DISABLED, false); }
            };
            default -> throw new IllegalStateException("Unknown email provider");
        };
    }

    private static Config aliyunConfig(MessagingProperties.Aliyun p) {
        check(p.getAccessKeyId() != null && !p.getAccessKeyId().isBlank()
                && p.getAccessKeySecret() != null && !p.getAccessKeySecret().isBlank(), "Aliyun credentials required");
        check(p.getEndpoint() != null && p.getEndpoint().matches("[a-z0-9.-]+\\.aliyuncs\\.com"), "Invalid Aliyun endpoint");
        var config = new Config().setAccessKeyId(p.getAccessKeyId()).setAccessKeySecret(p.getAccessKeySecret())
                .setEndpoint(p.getEndpoint()).setProtocol("https");
        if (p.getSecurityToken() != null && !p.getSecurityToken().isBlank()) {
            config.setSecurityToken(p.getSecurityToken()).setType("sts");
        }
        return config;
    }
    private static void productionProvider(Environment env, String provider, String mock) {
        check(!Arrays.asList(env.getActiveProfiles()).contains("prod") || !mock.equals(provider), "Simulated messaging provider forbidden in prod");
    }
    private static void check(boolean valid, String reason) { if (!valid) throw new IllegalStateException(reason); }
}
