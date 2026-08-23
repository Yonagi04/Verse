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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    private final LlmForwardService llmForwardService;

    @PostMapping("/chat/completions")
    public ResponseEntity<String> chatCompletion(@RequestBody String body) {
        UserContext ctx = UserContextHolder.get();
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        try {
            String response = llmForwardService.chatCompletion(ctx, body, requestId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_REQUEST_ID, requestId)
                    .body(response);
        } catch (AbstractException e) {
            return toOpenAiError(e, requestId);
        } catch (Exception e) {
            log.error("[llm-forward] 未预期异常: requestId={}", requestId, e);
            return toOpenAiError(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
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
    private ResponseEntity<String> toOpenAiError(AbstractException e, String requestId) {
        String code = e.getErrorCode();
        JSONObject error = new JSONObject();
        error.put("message", e.getErrorMessage());
        error.put("type", typeFor(code));
        error.put("code", code);
        JSONObject body = new JSONObject();
        body.put("error", error);
        return ResponseEntity.status(statusFor(code))
                .contentType(MediaType.APPLICATION_JSON)
                .header(HEADER_REQUEST_ID, requestId)
                .body(JSON.toJSONString(body));
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
