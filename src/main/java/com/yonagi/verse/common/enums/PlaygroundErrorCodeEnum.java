package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/** PlayGround 独立接口错误码。 */
public enum PlaygroundErrorCodeEnum implements IErrorCode {
    DISABLED("A001000", "当前租户未开启 PlayGround"),
    SESSION_NOT_FOUND("A001001", "会话不存在"),
    MODEL_UNAVAILABLE("A001002", "模型不可用或不支持文本聊天"),
    INVALID_PROMPT("A001003", "请输入纯文本提示词"),
    GENERATING("A001004", "该会话正在生成"),
    RPM_LIMIT("A001005", "已达到此模型的 PlayGround 每分钟请求上限"),
    RPH_LIMIT("A001006", "已达到此模型的 PlayGround 每小时请求上限"),
    SHARED_LIMIT("A001007", "已达到租户或模型共享限额"),
    CONTEXT_LIMIT("A001008", "模型上下文超限"),
    RISK_UNCONFIRMED("A001009", "开启功能未确认风险"),
    DUPLICATE_SEND("A001010", "该发送已受理，请刷新会话"),
    UNSUPPORTED_RESPONSE("A001011", "模型返回了不支持的内容"),
    INVALID_PAGE("A001012", "分页参数非法"),
    MODEL_LOCKED("A001013", "已有轮次的会话不能改模型"),
    UPSTREAM_ERROR("C001000", "上游模型调用失败"),
    INTERNAL_ERROR("B001000", "PlayGround 内部处理失败");

    private final String code;
    private final String message;

    PlaygroundErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override public String code() { return code; }
    @Override public String message() { return message; }
}
