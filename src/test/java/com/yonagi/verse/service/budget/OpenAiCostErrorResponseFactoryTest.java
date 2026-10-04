package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.web.OpenAiCostErrorResponseFactory;
import com.yonagi.verse.dto.resp.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCostErrorResponseFactoryTest {
    static CostLimitExceededException exceeded(String end) {
        return new CostLimitExceededException(new ApiKeyCostStatusRespDTO("123", new CostLimitConfig(true, null, "3000", "10000", "1"),
                "CNY", "Asia/Shanghai", "2026-09-30T18:00:00+08:00", "READY", "LIMITED", end, List.of(),
                List.of(new ApiKeyCostStatusRespDTO.Hit("API_KEY", "WEEK", end),
                        new ApiKeyCostStatusRespDTO.Hit("API_KEY", "MONTH", "2026-10-01T00:00:00+08:00"))));
    }
    @Test void allHitsLatestPeriodDynamicRetryAndNoAmountLeak() {
        var exception = exceeded("2026-10-05T00:00:00+08:00");
        String payload = OpenAiCostErrorResponseFactory.body(exception, "req-1");
        var error = JSON.parseObject(payload).getJSONObject("error");
        assertEquals("cost_limit_exceeded", error.getString("code"));
        assertEquals("WEEK", error.getString("period")); assertEquals(2, error.getJSONArray("limits").size());
        assertFalse(payload.contains("3000")); assertFalse(payload.contains("apiKeyId"));
        assertTrue(exception.getErrorMessage().contains("本周"));
        var response = OpenAiCostErrorResponseFactory.headers(exception, "req-1", Instant.parse("2026-10-04T15:59:58.5Z")).body(payload);
        assertEquals(429, response.getStatusCode().value()); assertEquals("2", response.getHeaders().getFirst("Retry-After"));
    }
    @Test void unavailableNeverInventsRecoveryOrZeroBalance() {
        var exception = new CostBudgetUnavailableException();
        var response = OpenAiCostErrorResponseFactory.headers(exception, "req-2", Instant.now())
                .body(OpenAiCostErrorResponseFactory.body(exception, "req-2"));
        assertEquals(503, response.getStatusCode().value()); assertNull(response.getHeaders().getFirst("Retry-After"));
        var error = JSON.parseObject(response.getBody()).getJSONObject("error");
        assertEquals("cost_budget_unavailable", error.getString("code")); assertFalse(error.containsKey("retry_at"));
    }
}
