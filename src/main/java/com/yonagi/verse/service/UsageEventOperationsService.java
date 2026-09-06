package com.yonagi.verse.service;

import com.yonagi.verse.dto.resp.UsageEventReconciliationRespDTO;

/** 计费用量事件运维操作。 */
public interface UsageEventOperationsService {
    UsageEventReconciliationRespDTO reconcile(Long userId, Long tenantId);

    boolean replay(Long userId, Long tenantId, String eventId);
}
