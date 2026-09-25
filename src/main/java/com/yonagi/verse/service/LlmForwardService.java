package com.yonagi.verse.service;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.service.forward.AdapterExchange;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Instant;

import java.util.List;

/**
 * LLM 转发服务 — 编排鉴权后的转发主链路（解析模型 → 解密 → 上游调用 → 记录 Token）。
 *
 * @author Yonagi
 */
public interface LlmForwardService {

    /**
     * 处理一次 chat/completions 转发请求。
     *
     * @param ctx       当前请求上下文（含 userId/tenantId/apiKeyId）
     * @param body      原始请求体（OpenAI 兼容 JSON）
     * @param requestId 请求追踪 ID
     * @return 上游响应体（OpenAI 兼容格式，含 usage）
     */
    String chatCompletion(UserContext ctx, String body, String requestId, Instant requestStartedAt);

    /** 按显式能力绑定转发非流式 JSON 操作。 */
    default String jsonCompletion(UserContext ctx, ModelOperation operation, String body,
                                  String requestId, Instant requestStartedAt) {
        throw new UnsupportedOperationException("operation unavailable");
    }

    /** Responses 保留原始命名 SSE 事件。 */
    default Flux<ServerSentEvent<String>> responsesStream(UserContext ctx, String body,
                                                           String requestId, Instant requestStartedAt) {
        throw new UnsupportedOperationException("responses stream unavailable");
    }

    /** 音频能力使用有类型的 multipart/JSON 输入与二进制输出。 */
    default AdapterExchange.Result media(UserContext ctx, ModelOperation operation, String modelAlias,
                                         AdapterExchange.Request request, String requestId,
                                         Instant requestStartedAt) {
        throw new UnsupportedOperationException("media unavailable");
    }

    /**
     * 处理一次流式（stream=true）chat/completions 转发请求，返回上游 SSE 事件流。
     *
     * @param ctx       当前请求上下文（含 userId/tenantId/apiKeyId）
     * @param body      原始请求体（OpenAI 兼容 JSON，stream=true）
     * @param requestId 请求追踪 ID
     * @return 上游 SSE 事件流（惰性，订阅时才连上游）
     */
    Flux<ServerSentEvent<String>> chatCompletionStream(UserContext ctx, String body,
                                                        String requestId, Instant requestStartedAt);

    /**
     * 列出当前租户下启用的模型别名（OpenAI /models 兼容）。
     *
     * @param tenantId 租户 ID
     * @return 启用模型别名列表
     */
    List<String> listModels(Long tenantId);
}
