package com.yonagi.verse.service;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.PlaygroundDtos;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** PlayGround 会话与调用入口。 */
public interface PlaygroundService {
    PlaygroundDtos.Status status(UserContext actor, Long tenantId);
    PlaygroundDtos.Models models(UserContext actor, Long tenantId);
    PlaygroundDtos.Summary create(UserContext actor, Long tenantId, Long serviceId);
    PlaygroundDtos.Sessions sessions(UserContext actor, Long tenantId, int pageNum, int pageSize, String keyword);
    PlaygroundDtos.Detail detail(UserContext actor, Long tenantId, Long sessionId);
    PlaygroundDtos.Summary updateModel(UserContext actor, Long tenantId, Long sessionId, Long serviceId);
    boolean delete(UserContext actor, Long tenantId, Long sessionId);
    PreparedTurn prepareSend(UserContext actor, Long tenantId, Long sessionId, String prompt,
                             String idempotencyKey);

    /** 前置检查与入库已完成；订阅时才连接上游。 */
    record PreparedTurn(String requestId, Flux<ServerSentEvent<String>> events) { }
}
