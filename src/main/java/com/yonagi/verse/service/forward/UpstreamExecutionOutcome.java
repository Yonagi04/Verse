package com.yonagi.verse.service.forward;

/** 能够证明的执行状态；客户端交付失败不等于上游未执行。 */
public enum UpstreamExecutionOutcome {
    NOT_SENT,
    REJECTED,
    /** 已收到协议结束证据；费用仍取决于有效用量和定价。 */
    COMPLETED,
    UNKNOWN
}
