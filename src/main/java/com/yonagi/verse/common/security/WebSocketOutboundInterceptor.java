package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.service.WebSocketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

/** 在出站执行器取出消息后复核会话，避免队列等待期间到期/撤销仍继续投递。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketOutboundInterceptor implements ExecutorChannelInterceptor {
    private final WebSocketSessionService sessions;

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return message;
        }
        try {
            sessions.requireActive(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
            return message;
        } catch (ClientException exception) {
            return null;
        } catch (RuntimeException exception) {
            // 不让 broker 再记录含完整通知正文的失败消息；本边界只记录验证依赖异常。
            log.warn("[ws] 会话验证依赖失败，已阻止消息投递", exception);
            return null;
        }
    }
}
