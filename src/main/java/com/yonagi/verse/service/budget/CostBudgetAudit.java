package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 沿用租户动态记录开关，拒绝与费用事实分离，不携带正文、金额或 Key 明文。 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CostBudgetAudit {
    private final TenantActivityRecorder recorder;
    private final ApiKeyMapper keys;
    @Transactional(propagation = Propagation.MANDATORY)
    public void configured(ApiKeyDO key) {
        recorder.record(key.getTenantId(), TenantActivityDraft.of(TenantActivityType.API_KEY_COST_CONFIGURED)
                .actor(key.getUserId()).target(TenantActivityTargetType.API_KEY, key.getApiKeyId(), key.getName())
                .detail("version", String.valueOf(key.getCostConfigVersion())).detail("enabled", key.getCostLimitEnabled()));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(UserContext ctx, String requestId, AbstractException exception) {
        if (ctx == null || ctx.getApiKeyId() == null || !com.yonagi.verse.common.web.OpenAiCostErrorResponseFactory.supports(exception)) return;
        ApiKeyDO key = keys.lockBudgetKey(ctx.getCurrentTenantId(), ctx.getApiKeyId());
        if (key == null) return;
        var draft = TenantActivityDraft.of(TenantActivityType.API_KEY_COST_REJECTED).actor(ctx.getUserId())
                .target(TenantActivityTargetType.API_KEY, key.getApiKeyId(), key.getName())
                .detail("version", String.valueOf(key.getCostConfigVersion())).detail("requestId", requestId)
                .detail("businessCode", exception.getErrorCode());
        if (exception instanceof CostLimitExceededException exceeded) {
            draft.detail("period", exceeded.getStatus().limits().getFirst().period()).detail("retryAt", exceeded.getStatus().retryAt());
        }
        recorder.record(key.getTenantId(), draft);
    }
}
