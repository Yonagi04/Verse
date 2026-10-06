package com.yonagi.verse.service.playground;

import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 缓存命中也执行 Playground 的完整身份约束。 */
@Component
@RequiredArgsConstructor
public class PlaygroundQueryAccess implements QueryAccessPolicy {
    private final PlaygroundAccessPolicy access;
    @Override public void check(Object[] args) { access.requireEnabled((UserContext) args[0], (Long) args[1]); }
}
