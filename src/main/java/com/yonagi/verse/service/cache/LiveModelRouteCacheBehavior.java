package com.yonagi.verse.service.cache;

import com.yonagi.verse.common.cache.QueryCacheBehavior;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.service.forward.impl.ModelResolverImpl;
import org.springframework.stereotype.Component;

/** 缓存只提供路由配置，资源和提供者的可用性在每次读取时复核。 */
@Component
public class LiveModelRouteCacheBehavior implements QueryCacheBehavior {
    @Override
    public Object currentView(Object target, Object[] args, Object cached) {
        ((ModelResolverImpl) target).requireAvailable((LlmServiceDO) cached);
        return cached;
    }
}
