package com.yonagi.verse.service;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.security.UserContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.util.List;

/** 二期工作台契约；所有正文操作均限定当前租户和创建者。 */
public interface PlaygroundWorkbenchService {
    List<JSONObject> models(UserContext actor, Long tenant);
    List<JSONObject> list(UserContext actor, Long tenant, String kind, String keyword);
    JSONObject create(UserContext actor, Long tenant, String kind, JSONObject body);
    JSONObject detail(UserContext actor, Long tenant, Long id);
    JSONObject update(UserContext actor, Long tenant, Long id, JSONObject body);
    boolean delete(UserContext actor, Long tenant, Long id);
    JSONObject round(UserContext actor, Long tenant, Long id, String prompt, String key);
    Flux<ServerSentEvent<String>> stream(UserContext actor, Long tenant, Long attempt);
    boolean stop(UserContext actor, Long tenant, Long attempt);
    JSONObject retry(UserContext actor, Long tenant, Long attempt, String key);
    JSONObject fork(UserContext actor, Long tenant, Long id, JSONObject body);
    JSONObject restore(UserContext actor, Long tenant, Long id, int version);
}
