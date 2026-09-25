package com.yonagi.verse.service.forward;

/** 由服务端组装的可信纯文本聊天消息。 */
public record ChatMessage(String role, String content) { }
