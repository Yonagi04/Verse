package com.yonagi.verse.common.web;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO;
import com.yonagi.verse.dto.resp.OpenAiCostErrorDetailDTO;
import com.yonagi.verse.dto.resp.OpenAiErrorRespDTO;
import com.yonagi.verse.service.budget.CostBudgetDecision;
import com.yonagi.verse.service.budget.CostBudgetUnavailableException;
import com.yonagi.verse.service.budget.CostLimitExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** 成本错误统一用于 JSON、二进制及 Rerank，不暴露金额或 Key 标识。 */
public final class OpenAiCostErrorResponseFactory {
    private static final String BUDGET_TIMEZONE = "Asia/Shanghai";

    private OpenAiCostErrorResponseFactory() { }

    /** 判断是否使用预算拒绝的专用错误契约。 */
    public static boolean supports(AbstractException error) {
        return error instanceof CostLimitExceededException || error instanceof CostBudgetUnavailableException;
    }

    /** 限额拒绝提供重试提示，预算不可用时无法给出恢复时间。 */
    public static ResponseEntity.BodyBuilder headers(AbstractException error, String requestId, Instant now) {
        HttpStatus status = error instanceof CostLimitExceededException
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON).header("x-request-id", requestId);
        if (error instanceof CostLimitExceededException exceeded) {
            Duration until = Duration.between(now, OffsetDateTime.parse(exceeded.getStatus().retryAt()).toInstant());
            long seconds = Math.max(1, until.getSeconds() + (until.getNano() == 0 ? 0 : 1));
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        }
        return builder;
    }

    /** 仅写出对客户端公开的预算状态，避免序列化内部费用快照。 */
    public static String body(AbstractException exception, String requestId) {
        OpenAiCostErrorDetailDTO error;
        if (exception instanceof CostLimitExceededException exceeded) {
            CostBudgetDecision status = exceeded.getStatus();
            ApiKeyCostStatusRespDTO.Hit primary = status.limits().getFirst();
            List<OpenAiCostErrorDetailDTO.LimitDTO> limits = status.limits().stream()
                    .map(hit -> new OpenAiCostErrorDetailDTO.LimitDTO(hit.scope(), hit.period(), hit.periodEnd()))
                    .toList();
            error = new OpenAiCostErrorDetailDTO(exception.getErrorMessage(), exception.getErrorCode(), requestId,
                    "rate_limit_error", "cost_limit_exceeded", primary.scope(), primary.period(), status.retryAt(),
                    BUDGET_TIMEZONE, limits);
        } else {
            error = new OpenAiCostErrorDetailDTO(exception.getErrorMessage(), exception.getErrorCode(), requestId,
                    "server_error", "cost_budget_unavailable", null, null, null, null, null);
        }
        return JSON.toJSONString(new OpenAiErrorRespDTO<>(error));
    }
}
