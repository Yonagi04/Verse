package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum CostReconciliationErrorCode implements IErrorCode {
    BATCH_UNAVAILABLE("B000820", "补账记录不存在或不可访问"),
    CASE_CHANGED("A000620", "账单状态已变化，请刷新后重试"),
    INVALID_EVIDENCE("A000621", "请核对上游证据和分类用量后再提交");

    private final String code;
    private final String message;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
