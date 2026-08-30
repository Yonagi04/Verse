package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * LLM 调用审计错误码（沿用 A/B/C 规范）
 *
 * @author Yonagi
 */
public enum LlmAuditErrorCodeEnum implements IErrorCode {

    AUDIT_LOG_NOT_FOUND("A000900", "审计记录不存在"),
    AUDIT_CONTENT_UNAVAILABLE("A000901", "审计内容不可用"),
    AUDIT_PAGINATION_PARAM_INVALID("A000902", "分页参数不合法"),

    AUDIT_S3_READ_FAILED("B000900", "审计内容读取失败"),
    ;

    private final String code;
    private final String message;

    LlmAuditErrorCodeEnum(String code, String message) {
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
