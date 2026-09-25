package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;

/** 上游错误映射仅提取有限消息，避免记录请求正文或媒体内容。 */
public final class UpstreamErrors {
    private UpstreamErrors() {}

    public static UpstreamFailureException from(int status, String response) {
        String message = LlmForwardErrorCodeEnum.UPSTREAM_ERROR.message();
        try {
            JSONObject object = JSON.parseObject(response);
            JSONObject error = object == null ? null : object.getJSONObject("error");
            String value = error == null ? null : error.getString("message");
            if (value != null && !value.isBlank()) message = value.substring(0, Math.min(value.length(), 512));
        } catch (RuntimeException ignored) {
            // 不将未知错误体原样回传，避免泄露媒体或凭据。
        }
        return new UpstreamFailureException(message, LlmForwardErrorCodeEnum.UPSTREAM_ERROR,
                status == 429 || status >= 500);
    }

    public static UpstreamFailureException timeout() {
        return new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, true);
    }
}
