package com.yonagi.verse.service.tenant;

import com.yonagi.verse.common.cache.*;
import com.yonagi.verse.dto.resp.TenantInviteListRespDTO;
import com.yonagi.verse.service.impl.TenantInviteServiceImpl;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** 缓存邀请候选，实时过滤到期记录并分页。 */
@Component
public class TenantInvitesCacheBehavior implements QueryCacheBehavior {
    @Override public List<Object> parameters(Object[] args) {
        // 所有分页共享候选集合；读取后实时过滤自然到期记录并分页。
        return new ArrayList<>(Arrays.asList(args).subList(0, 2));
    }
    @Override public Object load(Object target, Object[] args, QueryCache.Loader original) {
        return ((TenantInviteServiceImpl) target).inviteCandidates((Long) args[0], (Long) args[1]);
    }
    @Override public Object currentView(Object target, Object[] args, Object cached) {
        return ((TenantInviteServiceImpl) target).pageAvailableInvites((TenantInviteListRespDTO) cached,
                (Integer) args[2], (Integer) args[3]);
    }
}
