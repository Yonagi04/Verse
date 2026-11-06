package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpstreamErrorsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "{\"error\":{\"message\":\"sk-secret http://internal/account private prompt\"}}",
            "{\"error\":{\"message\":\"invalid input\"}}", "not-json"
    })
    void upstreamBodyCannotEnterPublicFailure(String body) {
        for (int status : new int[]{400, 408, 409, 429, 500, 503}) {
            UpstreamFailureException failure = UpstreamErrors.from(status, body);
            assertEquals(LlmForwardErrorCodeEnum.UPSTREAM_ERROR.message(), failure.getErrorMessage());
            assertEquals(LlmForwardErrorCodeEnum.UPSTREAM_ERROR.code(), failure.getErrorCode());
            assertEquals(status == 429 || status >= 500, failure.isRetryable());
            assertEquals(status >= 500 || status == 408 || status == 409
                    ? UpstreamExecutionOutcome.UNKNOWN : UpstreamExecutionOutcome.REJECTED,
                    failure.getExecutionOutcome());
        }
    }
}
