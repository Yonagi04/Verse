package com.yonagi.verse.common.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.service.budget.*;
import org.springframework.http.*;
import java.time.*;

/** 成本错误统一用于 JSON、二进制及 Rerank，不暴露金额或 Key 标识。 */
public final class OpenAiCostErrorResponseFactory {
    private OpenAiCostErrorResponseFactory() { }
    public static boolean supports(AbstractException error) {
        return error instanceof CostLimitExceededException || error instanceof CostBudgetUnavailableException;
    }
    public static ResponseEntity.BodyBuilder headers(AbstractException error, String requestId, Instant now) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(error instanceof CostLimitExceededException ? 429 : 503)
                .contentType(MediaType.APPLICATION_JSON).header("x-request-id", requestId);
        if (error instanceof CostLimitExceededException exceeded) {
            Duration until = Duration.between(now, OffsetDateTime.parse(exceeded.getStatus().retryAt()).toInstant());
            long seconds = Math.max(1, until.getSeconds() + (until.getNano() == 0 ? 0 : 1));
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        return builder;
    }
    public static String body(AbstractException exception, String requestId) {
        JSONObject error = new JSONObject();
        error.put("message", exception.getErrorMessage()); error.put("business_code", exception.getErrorCode());
        error.put("request_id", requestId);
        if (exception instanceof CostLimitExceededException exceeded) {
            var status = exceeded.getStatus(); var primary = status.limits().getFirst();
            error.put("type", "rate_limit_error"); error.put("code", "cost_limit_exceeded");
            error.put("scope", primary.scope()); error.put("period", primary.period());
            error.put("retry_at", status.retryAt()); error.put("timezone", "Asia/Shanghai");
            error.put("limits", status.limits().stream().map(hit -> {
                JSONObject item = new JSONObject(); item.put("scope", hit.scope()); item.put("period", hit.period());
                item.put("period_end", hit.periodEnd()); return item;
            }).toList());
        } else { error.put("type", "server_error"); error.put("code", "cost_budget_unavailable"); }
        JSONObject envelope = new JSONObject(); envelope.put("error", error); return JSON.toJSONString(envelope);
    }
}
