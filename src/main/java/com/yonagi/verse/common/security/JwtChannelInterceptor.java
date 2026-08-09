package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * STOMP CONNECT 帧 JWT 认证拦截器
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[ws] STOMP CONNECT 缺少 Authorization header");
                throw new IllegalArgumentException(BaseErrorCode.TOKEN_MISSING.message());
            }

            String token = authHeader.substring(7);
            if (!jwtUtil.validateToken(token)) {
                log.warn("[ws] STOMP CONNECT Token 无效或已过期");
                throw new IllegalArgumentException(BaseErrorCode.TOKEN_INVALID.message());
            }

            // 从 JWT 解析 userId，注入 Principal
            String userId = jwtUtil.parseToken(token).getSubject();
            accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            log.debug("[ws] STOMP 用户 {} 已连接", userId);
        }

        return message;
    }
}
