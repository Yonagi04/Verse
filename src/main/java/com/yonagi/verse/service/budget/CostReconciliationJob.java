package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostReconciliationWorkDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 启动仅唤醒有界 worker，HTTP 就绪不等待数据库扫描或通知。 */
@Slf4j
@Component
public class CostReconciliationJob {
    private static final int MAX_BATCHES_PER_WAKE = 5;
    private final TaskExecutor executor;
    private final CostReconciliationTracking tracking;
    private final CostReconciliationScanCoordinator coordinator;
    private final CostReconciliationDiscovery discovery;
    private final CostBudgetInvocationMapper invocations;
    private final BudgetExecutionRegistry registry;
    private final CostReconciliationNotifications notifications;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean ready;
    @Value("${verse.llm.cost-reconciliation.processing-enabled:true}")
    private boolean processingEnabled;

    public CostReconciliationJob(@Qualifier("costReconciliationExecutor") TaskExecutor executor,
                                 CostReconciliationTracking tracking, CostReconciliationScanCoordinator coordinator,
                                 CostReconciliationDiscovery discovery, CostBudgetInvocationMapper invocations,
                                 BudgetExecutionRegistry registry, CostReconciliationNotifications notifications) {
        this.executor = executor;
        this.tracking = tracking;
        this.coordinator = coordinator;
        this.discovery = discovery;
        this.invocations = invocations;
        this.registry = registry;
        this.notifications = notifications;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        ready = true;
        wake();
    }

    @Scheduled(fixedDelayString = "${verse.llm.cost-reconciliation.recovery-delay-ms:10000}")
    public void wake() {
        if (!ready || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::run);
        } catch (TaskRejectedException busy) {
            running.set(false);
            log.debug("[reconciliation] worker 暂不可用，等待下次唤醒");
        }
    }

    private void run() {
        try {
            if (!processingEnabled || !tracking.enabled()) {
                return;
            }
            for (int index = 0; index < MAX_BATCHES_PER_WAKE; index++) {
                var claims = coordinator.claim();
                if (claims.isEmpty()) {
                    break;
                }
                recoverBatch(claims);
            }
            for (var task : coordinator.expiredAutomatic()) {
                try {
                    discovery.expireAutomatic(task);
                } catch (RuntimeException failure) {
                    log.warn("[reconciliation] 自动任务超时转交待重试 requestId={}", task.getRequestId(), failure);
                }
            }
            notifications.stageReadyBatches();
        } catch (RuntimeException failure) {
            log.error("[reconciliation] 恢复轮次失败", failure);
        } finally {
            running.set(false);
        }
    }

    private void recoverBatch(List<CostReconciliationWorkDO> claims) {
        var observed = invocations.selectList(Wrappers.lambdaQuery(CostBudgetInvocationDO.class)
                .in(CostBudgetInvocationDO::getRequestId, claims.stream().map(CostReconciliationWorkDO::getRequestId).toList()));
        var indexed = observed.stream().collect(Collectors.toMap(CostBudgetInvocationDO::getRequestId, Function.identity()));
        var active = registry.confirmed(observed.stream().filter(row -> "RUNNING".equals(row.getState())).toList());
        for (var claim : claims) {
            recover(claim, indexed.get(claim.getRequestId()), active.contains(claim.getRequestId()));
        }
    }

    private void recover(CostReconciliationWorkDO claim, CostBudgetInvocationDO invocation, boolean active) {
        try {
            discovery.classify(claim, invocation, active);
        } catch (RuntimeException failure) {
            log.warn("[reconciliation] 分类或结算待重试 requestId={}", claim.getRequestId(), failure);
            coordinator.deferClassificationFailure(claim);
        }
    }
}
