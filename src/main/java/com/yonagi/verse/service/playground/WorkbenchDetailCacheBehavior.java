package com.yonagi.verse.service.playground;

import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.cache.QueryCacheBehavior;
import com.yonagi.verse.common.cache.QueryCacheTtl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 只缓存预设详情；生成会话仍实时读取，归属判断来自 Playground 领域。 */
@Component
@RequiredArgsConstructor
public class WorkbenchDetailCacheBehavior implements QueryCacheBehavior {
    private final PlaygroundAccessPolicy access;
    private final QueryCache cache;
    @Override public boolean supports(Object[] args) {
        UserContext actor = (UserContext) args[0];
        cache.check(() -> access.requireEnabled(actor, (Long) args[1]));
        String kind = cache.read("workbench-kind", RedisKeyConstant.PLAYGROUND_WORKBENCH_KIND_KEY,
                List.of(actor.getUserId(), args[1], args[2]), String.class, List.of("t_playground_workspace"),
                TimeUnit.SECONDS.toMillis(QueryCacheTtl.HOURS_4),
                () -> {
                    var workspace = access.findOwnedWorkspace(actor, (Long) args[1], (Long) args[2]);
                    return workspace == null ? null : workspace.getKind();
                });
        if (kind == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        return "PRESET".equals(kind);
    }
    @Override public void check(Object[] args) {
        access.requireWorkspace((UserContext) args[0], (Long) args[1], (Long) args[2]);
    }
}
