package com.yonagi.verse.service;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 按服务器连接隔离消息授权；连接存在期间禁止替换身份，真实断连后清理。 */
@Service
@RequiredArgsConstructor
public class WebSocketSessionService {
    private static final Set<String> SUBSCRIPTIONS = Set.of(
            "/user/queue/notifications", "/user/queue/notifications/unread-count");

    private final LoginSessionValidationService sessions;
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * 首次连接绑定身份；即使旧凭证过期，也不能在同一传输连接重新认证。
     *
     * @param connectionId 框架设置的服务器连接标识
     * @param token CONNECT 中的登录令牌
     * @return 用于用户私有目的地路由的身份
     * @throws ClientException 连接重复或会话不可用
     */
    public LoginSessionValidationService.Identity connect(String connectionId, String token) {
        if (!StringUtils.hasText(connectionId) || connections.containsKey(connectionId)) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        LoginSessionValidationService.Identity identity = sessions.authenticate(token);
        if (connections.putIfAbsent(connectionId, new Connection(identity, false)) != null) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        return identity;
    }

    /**
     * 默认拒绝业务发送及未知命令，只允许本人通知订阅和必要的连接控制帧。
     *
     * @param connectionId 服务器连接标识
     * @param command 帧命令；心跳为 null
     * @param destination 框架解析的目的地，订阅时必须精确匹配白名单
     * @throws ClientException 未认证、无效会话或目的地不允许
     */
    public void authorizeInbound(String connectionId, StompCommand command, String destination) {
        if (command == StompCommand.DISCONNECT) {
            // 先停止后续收发，但保留身份绑定，直到传输层实际结束。
            if (connectionId != null) {
                connections.computeIfPresent(connectionId, (id, previous) -> new Connection(previous.identity(), true));
            }
            return;
        }
        requireActive(connectionId);
        if (command == null || command == StompCommand.UNSUBSCRIBE) {
            return;
        }
        if (command == StompCommand.SUBSCRIBE && destination != null && SUBSCRIPTIONS.contains(destination)) {
            return;
        }
        throw new ClientException(BaseErrorCode.TOKEN_INVALID);
    }

    /**
     * 每次业务消息实际投递前复核，不因之前已经订阅而永久放行。
     *
     * @param connectionId broker 消息中的服务器连接标识
     * @throws ClientException 连接未认证、已停止或会话无效
     */
    public void requireActive(String connectionId) {
        Connection connection = connectionId == null ? null : connections.get(connectionId);
        if (connection == null || connection.closing()) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        sessions.requireActive(connection.identity());
    }

    /**
     * 传输层真实断连事件的幂等清理；不能仅凭客户端 DISCONNECT 帧调用。
     *
     * @param connectionId 已结束的服务器连接标识
     */
    public void disconnected(String connectionId) {
        if (connectionId != null) {
            connections.remove(connectionId);
        }
    }

    private record Connection(LoginSessionValidationService.Identity identity, boolean closing) {
    }
}
