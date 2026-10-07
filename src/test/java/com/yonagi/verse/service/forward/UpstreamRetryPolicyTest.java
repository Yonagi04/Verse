package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.ModelOperation;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamRetryPolicyTest {
    @Test void unknownExecutionCannotBeRetriedForAnyOperation() {
        for (ModelOperation operation : ModelOperation.values()) {
            assertFalse(UpstreamRetryPolicy.canRetry(operation, UpstreamErrors.timeout()));
            assertFalse(UpstreamRetryPolicy.canRetry(operation, UpstreamErrors.from(503, null)));
            assertFalse(UpstreamRetryPolicy.canRetry(operation, UpstreamErrors.from(408, null)));
            assertFalse(UpstreamRetryPolicy.canRetry(operation, new UpstreamFailureException("completed",
                    com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum.FORWARD_FAILED, true,
                    UpstreamExecutionOutcome.COMPLETED)));
        }
    }

    @Test void retryEligibilityDependsOnOperationAndProvenRejection() {
        for (ModelOperation operation : ModelOperation.values()) {
            boolean allowed = operation == ModelOperation.CHAT_COMPLETIONS || operation == ModelOperation.RESPONSES
                    || operation == ModelOperation.EMBEDDINGS || operation == ModelOperation.RERANK;
            assertEquals(allowed, UpstreamRetryPolicy.canRetry(operation, UpstreamErrors.from(429, null)));
            assertFalse(UpstreamRetryPolicy.canRetry(operation, UpstreamErrors.from(400, null)));
        }
    }

    @Test void onlyProvenConnectionFailuresAreNotSent() {
        assertEquals(UpstreamExecutionOutcome.NOT_SENT,
                UpstreamErrors.transport(new RuntimeException(new ConnectException())).getExecutionOutcome());
        assertEquals(UpstreamExecutionOutcome.NOT_SENT,
                UpstreamErrors.transport(new UnknownHostException()).getExecutionOutcome());
        assertEquals(UpstreamExecutionOutcome.UNKNOWN,
                UpstreamErrors.transport(new SocketTimeoutException()).getExecutionOutcome());
    }
}
