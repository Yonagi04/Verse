package com.yonagi.verse.common.messaging.provider.impl;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.common.messaging.provider.EmailProvider;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import java.nio.charset.StandardCharsets;

public final class MailpitEmailAdapter implements EmailProvider {
    private final JavaMailSender sender;
    private final MessagingProperties.Email config;
    public MailpitEmailAdapter(JavaMailSender sender, MessagingProperties.Email config) {
        this.sender = sender; this.config = config;
    }
    @Override public String name() { return "mailpit"; }
    @Override public MessageSubmissionRespDTO send(EmailSendReqDTO request) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, request.textBody() != null && request.htmlBody() != null,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(config.getAccountName(), config.getFromAlias());
            helper.setTo(request.toAddress());
            helper.setSubject(request.subject());
            if (request.textBody() != null && request.htmlBody() != null) helper.setText(request.textBody(), request.htmlBody());
            else if (request.htmlBody() != null) helper.setText(request.htmlBody(), true);
            else helper.setText(request.textBody(), false);
            // Message-ID 是关联信息，SMTP 服务端不保证去重。
            message.setHeader("Message-ID", "<" + request.requestId() + "@verse.local>");
            sender.send(message);
            String messageId = message.getMessageID();
            if (messageId != null && messageId.startsWith("<") && messageId.endsWith(">")) {
                messageId = messageId.substring(1, messageId.length() - 1);
            }
            return MessageSubmissionRespDTO.accepted(null, messageId);
        } catch (MailAuthenticationException e) {
            return MessageSubmissionRespDTO.rejected("SMTP_AUTH_REJECTED");
        } catch (Exception e) {
            // DATA 后断线可能已收信，不能通过 IOException 推断未受理。
            return ProviderTransportErrors.result(e);
        }
    }
}
