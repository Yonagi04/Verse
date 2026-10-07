package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 成功响应的有界读取；读取或解析失败仍可能已经产生上游费用。 */
final class UpstreamResponseBody {
    private UpstreamResponseBody() {}

    static byte[] read(InputStream body, int maxBytes) {
        try {
            byte[] bytes = body.readNBytes(Math.addExact(maxBytes, 1));
            if (bytes.length > maxBytes) {
                throw new UpstreamFailureException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE.message(),
                        LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE, false, UpstreamExecutionOutcome.UNKNOWN, false);
            }
            return bytes;
        } catch (IOException error) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                    LlmForwardErrorCodeEnum.FORWARD_FAILED, false, UpstreamExecutionOutcome.UNKNOWN, true, error);
        }
    }

    static String readJson(InputStream body, int maxBytes) {
        String json = new String(read(body, maxBytes), StandardCharsets.UTF_8);
        try {
            if (JSON.parseObject(json) == null) throw new IllegalArgumentException("empty upstream JSON");
            return json;
        } catch (RuntimeException error) {
            throw UpstreamErrors.responseFailure(error);
        }
    }
}
