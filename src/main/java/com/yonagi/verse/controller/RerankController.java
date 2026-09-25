package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.service.LlmForwardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Verse 自定义 Rerank 接口；认证由 /api/v1/rerank 的 API Key 过滤器执行。 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RerankController {
    private final LlmForwardService forwardService;

    @PostMapping(value = "/api/v1/rerank", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rerank(@RequestBody String body) {
        String requestId = String.valueOf(SnowflakeIdUtil.nextId());
        try {
            String result = forwardService.jsonCompletion(UserContextHolder.get(), ModelOperation.RERANK,
                    body, requestId, Instant.now());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header("x-request-id", requestId).body(result);
        } catch (AbstractException e) {
            return error(e, requestId);
        } catch (RuntimeException e) {
            log.error("[llm-forward] Rerank 失败: requestId={}", requestId, e);
            return error(new ServerException(LlmForwardErrorCodeEnum.FORWARD_FAILED), requestId);
        }
    }

    private ResponseEntity<String> error(AbstractException e, String requestId) {
            JSONObject error = new JSONObject();
            error.put("message", e.getErrorMessage());
            error.put("type", e.getErrorCode().startsWith("A") ? "invalid_request_error"
                    : e.getErrorCode().startsWith("C") ? "upstream_error" : "server_error");
            error.put("code", e.getErrorCode());
            JSONObject envelope = new JSONObject();
            envelope.put("error", error);
            int status = "A000800".equals(e.getErrorCode()) ? 404
                    : "A000801".equals(e.getErrorCode()) ? 401
                    : "A000802".equals(e.getErrorCode()) ? 429
                    : "A000806".equals(e.getErrorCode()) ? 413
                    : e.getErrorCode().startsWith("C") ? 502 : 400;
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .header("x-request-id", requestId).body(JSON.toJSONString(envelope));
    }
}
