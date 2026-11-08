package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.service.WebSocketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;

/**
 * STOMP 登录接入与默认拒绝的目的地授权。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final WebSocketSessionService sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            throw new IllegalArgumentException(BaseErrorCode.TOKEN_INVALID.message());
        }
        if (accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            // broker 的异常上下文和连接事件不应继续携带原始凭证。
            accessor.removeNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[ws] STOMP CONNECT 缺少 Authorization header");
                throw new IllegalArgumentException(BaseErrorCode.TOKEN_MISSING.message());
            }

            String token = authHeader.substring(7);
            String userId = sessions.connect(accessor.getSessionId(), token).userId().toString();
            accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            log.debug("[ws] STOMP 用户 {} 已连接", userId);
        } else {
            sessions.authorizeInbound(accessor.getSessionId(), accessor.getCommand(), accessor.getDestination());
        }

        return message;
    }

    /** 只在框架确认传输连接结束后清理，客户端 DISCONNECT 不能释放身份绑定。 */
    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        sessions.disconnected(event.getSessionId());
    }
}
