package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.forward.InFlightRequestCoalescer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.reactivestreams.Publisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.time.Instant;

/**
 * LLM 转发控制器 — OpenAI 兼容端点，不走 Result 包装，直接透传 OpenAI 响应。
 * 网关业务异常在此转为 OpenAI error 格式，避免落入 GlobalExceptionHandler 的 Result 包装。
 *
 * @author Yonagi
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/openai")
@RequiredArgsConstructor
public class LlmForwardController {

    private static final String HEADER_REQUEST_ID = "x-request-id";

    /**
     * 限流 429 的 Retry-After 秒数（RPM/TPM 均以 1 分钟为窗口）
     */
    private static final long RETRY_AFTER_SECONDS = 60L;

    private final LlmForwardService llmForwardService;
    private final InFlightRequestCoalescer inFlightRequestCoalescer;

    @PostMapping(
            value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<Publisher<String>> chatCompletion(@RequestBody String body) {
        UserContext ctx = UserContextHolder.get();
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        Instant requestStartedAt = Instant.now();
        if (isStreamRequest(body)) {
            return streamCompletion(ctx, body, requestId, requestStartedAt);
        }
        try {
            InFlightRequestCoalescer.CoalescedResponse response = inFlightRequestCoalescer.execute(
                    ctx,
                    body,
                    requestId,
                    () -> llmForwardService.chatCompletion(ctx, body, requestId, requestStartedAt)
            );
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_REQUEST_ID, response.requestId())
                    .body(Mono.just(response.body()));
        } catch (AbstractException e) {
            return toOpenAiError(e, requestId);
        } catch (Exception e) {
            log.error("[llm-forward] 未预期异常: requestId={}", requestId, e);
            return toOpenAiError(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
        }
    }

    /**
     * 流式转发：pre-flight 失败同步转 OpenAI JSON error；成功后返回 SSE 事件流。
     */
    private ResponseEntity<Publisher<String>> streamCompletion(UserContext ctx, String body,
                                                                String requestId, Instant requestStartedAt) {
        try {
            Flux<String> flux = llmForwardService.chatCompletionStream(ctx, body, requestId, requestStartedAt)
                    .map(this::toDataOnlySse);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header(HEADER_REQUEST_ID, requestId)
                    .body(flux);
        } catch (AbstractException e) {
            return toOpenAiError(e, requestId);
        } catch (Exception e) {
            log.error("[llm-forward] 流式转发未预期异常: requestId={}", requestId, e);
            return toOpenAiError(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
        }
    }

    /**
     * Spring MVC 以文本流逐块写出已经编码好的 data-only SSE 帧。
     * OpenAI Chat Completions 流只依赖 data 字段，末尾的 [DONE] 也按同样格式透传。
     */
    private String toDataOnlySse(ServerSentEvent<String> sse) {
        String data = sse.data();
        if (data == null) {
            return ":\n\n";
        }
        String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
        return "data: " + normalized.replace("\n", "\ndata: ") + "\n\n";
    }

    /**
     * 解析请求体判断是否流式（stream=true）。
     */
    private boolean isStreamRequest(String body) {
        try {
            JSONObject json = JSON.parseObject(body);
            return json != null && Boolean.TRUE.equals(json.getBoolean("stream"));
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/models")
    public ResponseEntity<String> listModels() {
        UserContext ctx = UserContextHolder.get();
        Long tenantId = ctx == null ? null : ctx.getCurrentTenantId();
        List<String> models = llmForwardService.listModels(tenantId);

        JSONObject body = new JSONObject();
        body.put("object", "list");
        List<JSONObject> data = models.stream()
                .map(name -> {
                    JSONObject item = new JSONObject();
                    item.put("id", name);
                    item.put("object", "model");
                    return item;
                })
                .toList();
        body.put("data", data);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(body));
    }

    /**
     * 业务异常 → OpenAI error 格式。
     */
    private ResponseEntity<Publisher<String>> toOpenAiError(AbstractException e, String requestId) {
        String code = e.getErrorCode();
        HttpStatus status = statusFor(code);
        JSONObject error = new JSONObject();
        error.put("message", e.getErrorMessage());
        error.put("type", typeFor(code));
        error.put("code", code);
        JSONObject body = new JSONObject();
        body.put("error", error);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HEADER_REQUEST_ID, requestId);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        }
        return builder.body(Mono.just(JSON.toJSONString(body)));
    }

    private String typeFor(String code) {
        if (code == null) {
            return "server_error";
        }
        if (code.startsWith("A")) {
            return "invalid_request_error";
        }
        if (code.startsWith("C")) {
            return "upstream_error";
        }
        return "server_error";
    }

    private HttpStatus statusFor(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case "A000800", "A000804" -> HttpStatus.NOT_FOUND;
            case "A000801" -> HttpStatus.UNAUTHORIZED;
            case "A000802" -> HttpStatus.TOO_MANY_REQUESTS;
            case "A000803" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "A000805" -> HttpStatus.GATEWAY_TIMEOUT;
            case "A000806" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "C000800" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
