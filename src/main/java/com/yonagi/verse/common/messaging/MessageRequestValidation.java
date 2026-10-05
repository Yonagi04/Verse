package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.enums.MessagingErrorCode;
import com.yonagi.verse.common.convention.exception.MessageSendException;

import java.nio.charset.StandardCharsets;

public final class MessageRequestValidation {
    private MessageRequestValidation() { }
    public static void require(boolean valid) {
        if (!valid) throw new MessageSendException(MessagingErrorCode.INVALID_REQUEST, false);
    }
    public static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && !value.contains("\r") && !value.contains("\n");
    }
    public static void context(String requestId, String scene, Long userId, Long tenantId) {
        require(requestId != null && requestId.matches("[A-Za-z0-9_.-]{1,64}"));
        require(scene != null && scene.matches("[A-Za-z0-9_.-]{1,64}"));
        require(userId == null || userId > 0);
        require(tenantId == null || tenantId > 0);
    }
    public static boolean email(String value) {
        return text(value, 254) && value.matches("[^\\s,@<>]+@[^\\s,@<>]+\\.[^\\s,@<>]+");
    }
    public static void body(String value) {
        require(value == null || value.getBytes(StandardCharsets.UTF_8).length <= 524288);
    }
}
