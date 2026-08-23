package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * LLM 转发链路错误码（对外为 OpenAI 兼容格式，沿用 A/B/C 规范）
 *
 * @author Yonagi
 */
public enum LlmForwardErrorCodeEnum implements IErrorCode {

    MODEL_NOT_FOUND("A000800", "模型不存在"),
    API_KEY_INVALID("A000801", "API Key 无效或已吊销"),
    RATE_LIMIT_EXCEEDED("A000802", "请求过于频繁"),
    MODEL_CIRCUIT_OPEN("A000803", "模型已熔断"),
    MODEL_NOT_CONFIGURED("A000804", "模型未配置"),
    UPSTREAM_TIMEOUT("A000805", "上游超时"),
    REQUEST_TOO_LARGE("A000806", "请求体过大"),

    FORWARD_FAILED("B000800", "转发失败"),
    ROUTER_FAILED("B000801", "路由判定失败"),

    UPSTREAM_ERROR("C000800", "上游模型返回错误"),
    ;

    private final String code;
    private final String message;

    LlmForwardErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
