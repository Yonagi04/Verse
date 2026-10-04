package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只重放原快照，永远不向供应商补发请求。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostBudgetRecoveryJob {
    private final CostBudgetService service;
    private final CostBudgetSettlementMapper settlements;
    private final CostBudgetInvocationMapper invocations;
    // 日常恢复保留固定间隔，重放待结算快照并续约执行心跳。
    @Scheduled(fixedDelay = 10_000)
    public void recover() {
        try {
            service.retryLocalSnapshots();
            for (var row : settlements.pending()) {
                try { service.apply(row.getEventId()); }
                catch (RuntimeException e) { log.error("[cost-budget] 补偿失败: eventId={}", row.getEventId(), e); }
            }
            for (var row : invocations.recoverable()) {
                try { service.recoverInvocation(row); }
                catch (RuntimeException e) { log.warn("[cost-budget] 执行确认失败: requestId={}", row.getRequestId(), e); }
            }
        } catch (RuntimeException e) { log.error("[cost-budget] 恢复扫描失败", e); }
    }
}
