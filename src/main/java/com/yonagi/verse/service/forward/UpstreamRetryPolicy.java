package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.ModelOperation;

/** 重试和备用模型共用同一策略，不允许通过换模型绕过重复执行保护。 */
public final class UpstreamRetryPolicy {
    private UpstreamRetryPolicy() {}

    public static boolean canRetry(ModelOperation operation, UpstreamFailureException failure) {
        if (!failure.isRetryable() || (failure.getExecutionOutcome() != UpstreamExecutionOutcome.NOT_SENT
                && failure.getExecutionOutcome() != UpstreamExecutionOutcome.REJECTED)) return false;
        return switch (operation) {
            case CHAT_COMPLETIONS, RESPONSES, EMBEDDINGS, RERANK -> true;
            case IMAGE_GENERATION, TRANSCRIPTION, SPEECH -> false;
        };
    }
}
