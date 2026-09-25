package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.enums.ModelOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 合并同一 API Key 下仍在执行的相同非流式请求，避免调用方超时重发造成重复上游调用、审计和计费。
 */
@Slf4j
@Component
public class InFlightRequestCoalescer {

    private final ConcurrentMap<RequestKey, InFlightRequest> inFlight = new ConcurrentHashMap<>();

    /**
     * 执行或复用一个仍在进行中的请求；首个请求结束后立即移除，不缓存历史响应。
     */
    public CoalescedResponse execute(UserContext context, String body, String requestId, Supplier<String> action) {
        return execute(context, ModelOperation.CHAT_COMPLETIONS, "application/json", body, requestId, action);
    }

    /** 仅对安全、有限的 JSON 请求合并；操作和媒体类型参与键计算。 */
    public CoalescedResponse execute(UserContext context, ModelOperation operation, String contentType,
                                     String body, String requestId, Supplier<String> action) {
        if (operation == ModelOperation.IMAGE_GENERATION || operation == ModelOperation.SPEECH
                || operation == ModelOperation.TRANSCRIPTION || contentType == null
                || !contentType.toLowerCase(java.util.Locale.ROOT).endsWith("json")
                || body != null && body.length() > 1_048_576) {
            return new CoalescedResponse(requestId, action.get());
        }
        if (context == null || context.getCurrentTenantId() == null || context.getApiKeyId() == null) {
            return new CoalescedResponse(requestId, action.get());
        }

        RequestKey key = new RequestKey(
                context.getCurrentTenantId(),
                context.getApiKeyId(),
                operation,
                contentType,
                sha256(body == null ? "" : body)
        );
        InFlightRequest candidate = new InFlightRequest(requestId, new CompletableFuture<>());
        InFlightRequest existing = inFlight.putIfAbsent(key, candidate);
        if (existing != null) {
            log.info("[llm-forward] 合并重复的进行中请求: requestId={}, originalRequestId={}",
                    requestId, existing.requestId());
            return await(existing);
        }

        try {
            String response = action.get();
            candidate.response().complete(response);
            return new CoalescedResponse(requestId, response);
        } catch (RuntimeException | Error e) {
            candidate.response().completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, candidate);
        }
    }

    private CoalescedResponse await(InFlightRequest request) {
        try {
            return new CoalescedResponse(request.requestId(), request.response().join());
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private record RequestKey(Long tenantId, Long apiKeyId, ModelOperation operation,
                              String contentType, String bodyDigest) {
    }

    private record InFlightRequest(String requestId, CompletableFuture<String> response) {
    }

    /** 合并后的响应及其原始请求追踪 ID。 */
    public record CoalescedResponse(String requestId, String body) {
    }
}
