package com.yonagi.verse.service.playground;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.cache.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.service.impl.PlaygroundWorkbenchServiceImpl;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** 模型元数据缓存后实时计算价格。 */
@Component
public class WorkbenchModelsCacheBehavior implements QueryCacheBehavior {
    @Override public Object load(Object target, Object[] args, QueryCache.Loader original) {
        return ((PlaygroundWorkbenchServiceImpl) target).modelMetadata((UserContext) args[0], (Long) args[1]);
    }
    @Override @SuppressWarnings("unchecked")
    public Object currentView(Object target, Object[] args, Object cached) {
        return ((PlaygroundWorkbenchServiceImpl) target).withCurrentPrices((Long) args[1], (List<JSONObject>) cached);
    }
}
