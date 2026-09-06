package com.yonagi.verse.dto.resp;

import lombok.Data;

/** 计费用量事件链路对账结果。 */
@Data
public class UsageEventReconciliationRespDTO {
    /** 尚未发送的事件数。 */
    private Long pendingCount;
    /** 等待自动重试的事件数。 */
    private Long retryCount;
    /** 等待人工重放的事件数。 */
    private Long failedCount;
    /** Broker 已接收但事实表尚不存在的事件数。 */
    private Long publishedMissingFactCount;
}
