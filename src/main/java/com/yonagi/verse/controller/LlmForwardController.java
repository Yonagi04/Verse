package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.service.forward.AdapterExchange;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Value("${verse.llm.media.max-upload-bytes:26214400}")
    private long maxUploadBytes = 25L * 1024 * 1024;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Publisher<String>> oversizedMultipart(MaxUploadSizeExceededException ignored) {
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        return toOpenAiError(new ClientException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE), requestId);
    }

    @PostMapping(value = "/audio/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(@RequestParam("model") String model,
                                         @RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "response_format", required = false) String format,
                                         @RequestParam(value = "language", required = false) String language,
                                         @RequestParam(value = "prompt", required = false) String prompt,
                                         @RequestParam(value = "temperature", required = false) String temperature) {
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        try {
            if (file.getSize() > maxUploadBytes) throw new ClientException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE);
            if (file.getContentType() == null) throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
            Map<String, String> fields = new LinkedHashMap<>();
            if (format != null) fields.put("response_format", format);
            if (language != null) fields.put("language", language);
            if (prompt != null) fields.put("prompt", prompt);
            if (temperature != null) fields.put("temperature", temperature);
            AdapterExchange.MultipartRequest request = new AdapterExchange.MultipartRequest(
                    ModelOperation.TRANSCRIPTION, fields, file.getOriginalFilename(),
                    MediaType.parseMediaType(file.getContentType()), file.getBytes());
            AdapterExchange.Result result = llmForwardService.media(UserContextHolder.get(),
                    ModelOperation.TRANSCRIPTION, model, request, requestId, Instant.now());
            AdapterExchange.BinaryResult binary = (AdapterExchange.BinaryResult) result;
            return ResponseEntity.ok().contentType(binary.contentType())
                    .header(HEADER_REQUEST_ID, requestId).body(binary.body());
        } catch (AbstractException e) {
            return toOpenAiMediaError(e, requestId);
        } catch (Exception e) {
            log.error("[llm-forward] 转写失败: requestId={}", requestId, e);
            return toOpenAiMediaError(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
        }
    }

    @PostMapping(value = "/audio/speech", produces = {"audio/mpeg", "audio/opus", "audio/aac", "audio/flac", "audio/wav", "audio/pcm", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> speech(@RequestBody String body) {
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        try {
            JSONObject json = JSON.parseObject(body);
            if (json == null || json.getString("model") == null) {
                throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
            }
            AdapterExchange.Result result = llmForwardService.media(UserContextHolder.get(), ModelOperation.SPEECH,
                    json.getString("model"), new AdapterExchange.JsonRequest(ModelOperation.SPEECH, json),
                    requestId, Instant.now());
            AdapterExchange.BinaryResult binary = (AdapterExchange.BinaryResult) result;
            return ResponseEntity.ok().contentType(binary.contentType())
                    .header(HEADER_REQUEST_ID, requestId).body(binary.body());
        } catch (AbstractException e) {
            return toOpenAiMediaError(e, requestId);
        } catch (Exception e) {
            log.error("[llm-forward] 语音生成失败: requestId={}", requestId, e);
            return toOpenAiMediaError(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
        }
    }

    @PostMapping(value = "/responses", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<Publisher<String>> responses(@RequestBody String body) {
        return jsonOperation(ModelOperation.RESPONSES, body);
    }

    @PostMapping(value = "/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Publisher<String>> embeddings(@RequestBody String body) {
        return jsonOperation(ModelOperation.EMBEDDINGS, body);
    }

    @PostMapping(value = "/images/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Publisher<String>> images(@RequestBody String body) {
        return jsonOperation(ModelOperation.IMAGE_GENERATION, body);
    }

    private ResponseEntity<Publisher<String>> jsonOperation(ModelOperation operation, String body) {
        UserContext ctx = UserContextHolder.get();
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        Instant started = Instant.now();
        try {
            if (operation == ModelOperation.RESPONSES && isStreamRequest(body)) {
                Flux<String> events = llmForwardService.responsesStream(ctx, body, requestId, started)
                        .map(this::toNamedSse);
                return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header(HEADER_REQUEST_ID, requestId).body(events);
            }
            String response;
            String responseId = requestId;
            if (operation == ModelOperation.IMAGE_GENERATION) {
                response = llmForwardService.jsonCompletion(ctx, operation, body, requestId, started);
            } else {
                InFlightRequestCoalescer.CoalescedResponse result = inFlightRequestCoalescer.execute(
                        ctx, operation, MediaType.APPLICATION_JSON_VALUE, body, requestId,
                        () -> llmForwardService.jsonCompletion(ctx, operation, body, requestId, started));
                response = result.body();
                responseId = result.requestId();
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_REQUEST_ID, responseId).body(Mono.just(response));
        } catch (AbstractException e) {
            return toOpenAiError(e, requestId);
        } catch (Exception e) {
            log.error("[llm-forward] JSON 能力转发失败: requestId={}", requestId, e);
            return toOpenAiError(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
        }
    }

    private String toNamedSse(ServerSentEvent<String> sse) {
        StringBuilder frame = new StringBuilder();
        if (sse.id() != null) frame.append("id: ").append(sse.id()).append('\n');
        if (sse.event() != null) frame.append("event: ").append(sse.event()).append('\n');
        if (sse.data() != null) {
            String normalized = sse.data().replace("\r\n", "\n").replace('\r', '\n');
            frame.append("data: ").append(normalized.replace("\n", "\ndata: ")).append('\n');
        }
        return frame.append('\n').toString();
    }

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
        ResponseEntity.BodyBuilder builder = errorHeaders(status, requestId);
        return builder.body(Mono.just(openAiErrorJson(e)));
    }

    /** 二进制接口的错误体必须是实际 JSON 字符串，不能把 Mono 交给 Jackson 序列化。 */
    private ResponseEntity<String> toOpenAiMediaError(AbstractException e, String requestId) {
        return errorHeaders(statusFor(e.getErrorCode()), requestId).body(openAiErrorJson(e));
    }

    private ResponseEntity.BodyBuilder errorHeaders(HttpStatus status, String requestId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HEADER_REQUEST_ID, requestId);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        }
        return builder;
    }

    private String openAiErrorJson(AbstractException e) {
        String code = e.getErrorCode();
        JSONObject error = new JSONObject();
        error.put("message", e.getErrorMessage());
        error.put("type", typeFor(code));
        error.put("code", code);
        JSONObject body = new JSONObject();
        body.put("error", error);
        return JSON.toJSONString(body);
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
