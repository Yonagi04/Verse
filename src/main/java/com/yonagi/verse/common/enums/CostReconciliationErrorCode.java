package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum CostReconciliationErrorCode implements IErrorCode {
    BATCH_UNAVAILABLE("B000820", "补账记录不存在或不可访问"),
    CASE_CHANGED("A000620", "账单状态已变化，请刷新后重试"),
    INVALID_EVIDENCE("A000621", "请核对上游证据和分类用量后再提交"),
    /** 一次性交接证明不存在、已过期或已被消费。 */
    HANDOFF_INVALID("A000622", "补账登录已失效，请重新连接 Verse"),
    /** 来源与配置的主站或补账源不匹配。 */
    HANDOFF_ORIGIN_DENIED("A000623", "补账登录来源无效"),
    /** 同一账号在窗口内发起过多交接。 */
    HANDOFF_RATE_LIMITED("A000624", "连接过于频繁，请稍后重试");

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
