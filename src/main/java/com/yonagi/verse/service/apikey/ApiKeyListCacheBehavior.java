package com.yonagi.verse.service.apikey;

import com.yonagi.verse.common.cache.*;
import com.yonagi.verse.dto.resp.ApiKeyPageRespDTO;
import com.yonagi.verse.service.impl.ApiKeyServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** 缓存 Key 元数据，实时补充使用与到期状态。 */
@Component
@RequiredArgsConstructor
public class ApiKeyListCacheBehavior implements QueryCacheBehavior {
    private final QueryCache cache;
    @Override public Object load(Object target, Object[] args, QueryCache.Loader original) {
        return ((ApiKeyServiceImpl) target).listApiKeyMetadata(
                (Long) args[0], (Long) args[1], (Integer) args[2], (Integer) args[3]);
    }
    @Override public Object currentView(Object target, Object[] args, Object cached) {
        // 高频使用时间只按当前页批量回源，不让每次模型调用清空稳定列表。
        return cache.check(() -> ((ApiKeyServiceImpl) target).withCurrentListState(
                (Long) args[0], (Long) args[1], (ApiKeyPageRespDTO) cached));
    }
}
