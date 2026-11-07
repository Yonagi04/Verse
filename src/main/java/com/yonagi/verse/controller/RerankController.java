package com.yonagi.verse.controller;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.common.web.OpenAiCostErrorResponseFactory;
import com.yonagi.verse.dto.resp.OpenAiErrorRespDTO;
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

    /** 转发 Rerank 请求并返回兼容错误信封。 */
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
        if (OpenAiCostErrorResponseFactory.supports(e)) {
            return OpenAiCostErrorResponseFactory.headers(e, requestId, Instant.now())
                    .body(OpenAiCostErrorResponseFactory.body(e, requestId));
        }
        String code = e.getErrorCode();
        int status = "A000800".equals(code) ? 404
                : "A000801".equals(code) ? 401
                : "A000802".equals(code) ? 429
                : "A000806".equals(code) ? 413
                : code.startsWith("C") ? 502 : 400;
        OpenAiErrorRespDTO<OpenAiErrorRespDTO.DetailDTO> envelope =
                OpenAiErrorRespDTO.gatewayError(e.getErrorMessage(), code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("x-request-id", requestId).body(JSON.toJSONString(envelope));
    }
}
