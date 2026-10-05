package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MessagingErrorCode implements IErrorCode {
    INVALID_REQUEST("B001100", "发送参数或短信模板不正确"),
    REQUEST_CONFLICT("B001101", "发送请求标识已被其他内容使用"),
    DAILY_LIMIT("B001102", "验证码发送次数已达上限，请稍后再试"),
    DISABLED("A001100", "消息发送服务未启用"),
    REJECTED("A001101", "消息发送请求被服务商拒绝，请稍后再试"),
    UNKNOWN("A001102", "发送结果暂时无法确认，如已收到验证码可继续验证，请勿立即重发"),
    RECORD_FAILED("A001103", "发送记录保存失败，请稍后再试"),
    STATE_FAILED("A001104", "验证码状态保存失败，请稍后再试"),
    BUSY("A001105", "消息发送服务繁忙，请稍后再试"),
    TRANSACTION_ACTIVE("A001106", "消息发送不能在业务数据库事务中执行");

    private final String code;
    private final String message;
    @Override public String code() { return code; }
    @Override public String message() { return message; }
}
