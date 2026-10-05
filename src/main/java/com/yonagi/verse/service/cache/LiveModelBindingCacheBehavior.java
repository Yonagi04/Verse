package com.yonagi.verse.service.cache;

import com.yonagi.verse.common.cache.QueryCacheBehavior;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.service.forward.impl.ModelResolverImpl;
import org.springframework.stereotype.Component;

/** 协议缓存命中也须复核资源，覆盖备用模型和 Playground 的绑定读取。 */
@Component
public class LiveModelBindingCacheBehavior implements QueryCacheBehavior {
    @Override
    public Object currentView(Object target, Object[] args, Object cached) {
        ((ModelResolverImpl) target).requireAvailable((LlmServiceDO) args[0]);
        return cached;
    }
}
