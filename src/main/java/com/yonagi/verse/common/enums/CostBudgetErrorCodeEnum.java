package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.AllArgsConstructor;

/** 成本预算独立于模型故障及请求限流。 */
@AllArgsConstructor
public enum CostBudgetErrorCodeEnum implements IErrorCode {
    INVALID_CONFIG("A000604", "成本限额须为有效的正整数分，开启时至少配置一个周期"),
    VERSION_CONFLICT("A000605", "成本配置已更新，请刷新当前配置后重试"),
    NOT_READY("A000606", "成本数据尚未就绪或计费未开启，暂不能开启成本限额"),
    LIMIT_EXCEEDED("A000809", "此 API Key 的调用成本已达到限额"),
    UNAVAILABLE("B000802", "预算校验暂不可用，请稍后重试");

    private final String code;
    private final String message;
    public String code() { return code; }
    public String message() { return message; }
}
