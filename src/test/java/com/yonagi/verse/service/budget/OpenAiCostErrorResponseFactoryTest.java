package com.yonagi.verse.service.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.web.OpenAiCostErrorResponseFactory;
import com.yonagi.verse.dto.resp.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCostErrorResponseFactoryTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    static CostLimitExceededException exceeded(String end) {
        return new CostLimitExceededException(new ApiKeyCostStatusRespDTO("123", new CostLimitConfig(true, null, "3000", "10000", "1"),
                "CNY", "Asia/Shanghai", "2026-09-30T18:00:00+08:00", "READY", "LIMITED", end, List.of(),
                List.of(new ApiKeyCostStatusRespDTO.Hit("API_KEY", "WEEK", end),
                        new ApiKeyCostStatusRespDTO.Hit("API_KEY", "MONTH", "2026-10-01T00:00:00+08:00"))));
    }
    @Test void allHitsLatestPeriodDynamicRetryAndNoAmountLeak() throws Exception {
        var exception = exceeded("2026-10-05T00:00:00+08:00");
        String payload = OpenAiCostErrorResponseFactory.body(exception, "req-1");
        JsonNode error = JSON_MAPPER.readTree(payload).path("error");
        assertEquals("cost_limit_exceeded", error.path("code").textValue());
        assertEquals(exception.getErrorCode(), error.path("business_code").textValue());
        assertEquals("req-1", error.path("request_id").textValue());
        assertEquals("WEEK", error.path("period").textValue());
        assertEquals("2026-10-05T00:00:00+08:00", error.path("retry_at").textValue());
        assertEquals(2, error.path("limits").size());
        assertEquals("2026-10-01T00:00:00+08:00", error.at("/limits/1/period_end").textValue());
        assertFalse(payload.contains("3000")); assertFalse(payload.contains("apiKeyId"));
        assertTrue(exception.getErrorMessage().contains("本周"));
        var response = OpenAiCostErrorResponseFactory.headers(exception, "req-1", Instant.parse("2026-10-04T15:59:58.5Z")).body(payload);
        assertEquals(429, response.getStatusCode().value()); assertEquals("2", response.getHeaders().getFirst("Retry-After"));
    }
    @Test void unavailableNeverInventsRecoveryOrZeroBalance() throws Exception {
        var exception = new CostBudgetUnavailableException();
        var response = OpenAiCostErrorResponseFactory.headers(exception, "req-2", Instant.now())
                .body(OpenAiCostErrorResponseFactory.body(exception, "req-2"));
        assertEquals(503, response.getStatusCode().value()); assertNull(response.getHeaders().getFirst("Retry-After"));
        JsonNode error = JSON_MAPPER.readTree(response.getBody()).path("error");
        assertEquals("cost_budget_unavailable", error.path("code").textValue());
        assertEquals(5, error.size());
        assertFalse(error.has("retry_at"));
    }
}
