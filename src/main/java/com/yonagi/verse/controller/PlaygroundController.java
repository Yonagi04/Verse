package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.PlaygroundDtos;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.PlaygroundService;
import com.yonagi.verse.service.impl.PlaygroundServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** JWT 成员的 PlayGround 私有接口。 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/playground")
@RequiredArgsConstructor
public class PlaygroundController {
    private final PlaygroundService playgroundService;

    @GetMapping("/status")
    public Result<PlaygroundDtos.Status> status(@PathVariable Long tenantId) {
        return Results.success(playgroundService.status(UserContextHolder.get(), tenantId));
    }

    @GetMapping("/models")
    public Result<PlaygroundDtos.Models> models(@PathVariable Long tenantId) {
        return Results.success(playgroundService.models(UserContextHolder.get(), tenantId));
    }

    @PostMapping("/sessions")
    public Result<PlaygroundDtos.Summary> create(@PathVariable Long tenantId,
            @RequestBody Map<String, Object> body) {
        return Results.success(playgroundService.create(UserContextHolder.get(), tenantId,
                serviceId(body)));
    }

    @GetMapping("/sessions")
    public Result<PlaygroundDtos.Sessions> sessions(@PathVariable Long tenantId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return Results.success(playgroundService.sessions(UserContextHolder.get(), tenantId,
                pageNum, pageSize, keyword));
    }

    @GetMapping("/sessions/{sessionId}")
    public Result<PlaygroundDtos.Detail> detail(@PathVariable Long tenantId, @PathVariable Long sessionId) {
        return Results.success(playgroundService.detail(UserContextHolder.get(), tenantId, sessionId));
    }

    @PostMapping("/sessions/{sessionId}/model/update")
    public Result<PlaygroundDtos.Summary> updateModel(@PathVariable Long tenantId,
            @PathVariable Long sessionId, @RequestBody Map<String, Object> body) {
        return Results.success(playgroundService.updateModel(UserContextHolder.get(), tenantId,
                sessionId, serviceId(body)));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Boolean> delete(@PathVariable Long tenantId, @PathVariable Long sessionId) {
        return Results.success(playgroundService.delete(UserContextHolder.get(), tenantId, sessionId));
    }

    @PostMapping(value = "/sessions/{sessionId}/turns/stream", produces = {
            MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter stream(@PathVariable Long tenantId, @PathVariable Long sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body, HttpServletResponse response) {
        if (body == null || body.size() != 1 || !body.containsKey("prompt")
                || !(body.get("prompt") instanceof String prompt)) {
            throw new ClientException(PlaygroundErrorCodeEnum.INVALID_PROMPT);
        }
        PlaygroundService.PreparedTurn prepared = playgroundService.prepareSend(
                UserContextHolder.get(), tenantId, sessionId, prompt, idempotencyKey);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Request-Id", prepared.requestId());
        SseEmitter emitter = new SseEmitter(130_000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable cancel = () -> {
            cancelled.set(true);
            Disposable current = subscription.get();
            if (current != null) current.dispose();
        };
        // Servlet 完成、超时或网络异常都取消 Reactor 订阅，触发轮次 STOPPED 收敛。
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        Disposable current = prepared.events().subscribe(event -> {
            try {
                emitter.send(SseEmitter.event().name(event.event()).data(event.data(), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException disconnected) {
                cancel.run();
            }
        }, error -> emitter.completeWithError(error), emitter::complete);
        subscription.set(current);
        if (cancelled.get()) current.dispose();
        return emitter;
    }

    private Long serviceId(Map<String, Object> body) {
        if (body == null || body.size() != 1 || !body.containsKey("serviceId")
                || !(body.get("serviceId") instanceof String value)) {
            throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE);
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE);
        }
    }

    /** 流式前置错误显式返回 JSON 和 HTTP 状态。 */
    @ExceptionHandler(AbstractException.class)
    public ResponseEntity<Result<?>> handle(AbstractException error) {
        HttpStatus status = switch (error.getErrorCode()) {
            case "A001000" -> HttpStatus.FORBIDDEN;
            case "A001001" -> HttpStatus.NOT_FOUND;
            case "A001002", "A001004", "A001010", "A001013" -> HttpStatus.CONFLICT;
            case "A001005", "A001006", "A001007" -> HttpStatus.TOO_MANY_REQUESTS;
            case "A001011" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "C001000" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        Result<Object> result = new Result<>().setCode(error.getErrorCode())
                .setMessage(error.getErrorMessage());
        if (error instanceof PlaygroundRateLimiter.LimitException limit) {
            result.setData(Map.of("reason", limit.getReason(),
                    "retryAfterSeconds", limit.getRetryAfterSeconds()));
        } else if (error instanceof PlaygroundServiceImpl.DuplicateSendException duplicate) {
            result.setData(Map.of("turnId", duplicate.original().getTurnId().toString(),
                    "status", duplicate.original().getStatus()));
        } else if ("A001007".equals(error.getErrorCode())) {
            result.setData(Map.of("reason", "SHARED_LIMIT"));
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(result);
    }
}
