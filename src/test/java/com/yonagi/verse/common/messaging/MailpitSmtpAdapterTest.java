package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.messaging.provider.impl.MailpitEmailAdapter;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真 SMTP socket fixture；验证 UTF-8 MIME 及 DATA 后断线的不确定语义。 */
class MailpitSmtpAdapterTest {
    @Test void sendsUtf8MimeAndDoesNotRetryAfterLostAcknowledgment() throws Exception {
        for (boolean lostAcknowledgment : new boolean[]{false, true}) {
            try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                 var pool = Executors.newSingleThreadExecutor()) {
                Future<String> captured = pool.submit(() -> {
                    try (var client = socket.accept()) {
                        client.setSoTimeout(5000);
                        var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                        var out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
                        out.print("220 fixture SMTP\r\n"); out.flush();
                        String mime = "";
                        for (String line; (line = in.readLine()) != null;) {
                            if (line.startsWith("EHLO") || line.startsWith("HELO")) out.print("250 fixture\r\n");
                            else if (line.equals("DATA")) {
                                out.print("354 send data\r\n"); out.flush();
                                StringBuilder data = new StringBuilder();
                                while ((line = in.readLine()) != null && !line.equals(".")) data.append(line).append("\r\n");
                                mime = data.toString();
                                if (lostAcknowledgment) return mime;
                                out.print("250 queued\r\n");
                            } else if (line.equals("QUIT")) { out.print("221 bye\r\n"); out.flush(); break; }
                            else out.print("250 OK\r\n");
                            out.flush();
                        }
                        return mime;
                    }
                });
                var sender = new JavaMailSenderImpl(); sender.setHost("127.0.0.1"); sender.setPort(socket.getLocalPort());
                sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "1000");
                sender.getJavaMailProperties().put("mail.smtp.timeout", "1000");
                sender.getJavaMailProperties().put("mail.smtp.writetimeout", "1000");
                var config = new MessagingProperties.Email(); config.setAccountName("sender@verse.test");
                var adapter = new MailpitEmailAdapter(sender, config);
                var request = new EmailSendReqDTO("smtp-1", "TEST", null, null, "user@verse.test", "测试邮件", "纯文本", "<p>HTML内容</p>");
                var result = adapter.send(request);
                assertEquals(lostAcknowledgment ? MessageSubmissionStatus.UNKNOWN : MessageSubmissionStatus.ACCEPTED, result.status());
                if (!lostAcknowledgment) assertNotNull(result.providerMessageId());
                var mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(captured.get(5, TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
                assertEquals("测试邮件", mime.getSubject()); assertEquals("user@verse.test", mime.getAllRecipients()[0].toString());
                assertTrue(mime.isMimeType("multipart/*"));
            }
        }
    }
}
