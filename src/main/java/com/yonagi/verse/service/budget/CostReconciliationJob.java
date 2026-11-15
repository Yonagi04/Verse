package com.yonagi.verse.service.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只有启动与北京时间每天 08:00 可发现新候选，调度线程不等待数据库。 */
@Slf4j
@Component
public class CostReconciliationJob {
    private final TaskExecutor executor;
    private final CostReconciliationTracking tracking;
    private final CostReconciliationProducer producer;
    private final CostReconciliationControlService controls;
    private final AtomicBoolean bootRunning = new AtomicBoolean();
    private final AtomicBoolean dailyRunning = new AtomicBoolean();
    private final String bootId = UUID.randomUUID().toString();
    private volatile boolean ready;
    @Value("${verse.llm.cost-reconciliation.processing-enabled:true}")
    private boolean processingEnabled;

    public CostReconciliationJob(@Qualifier("costReconciliationExecutor") TaskExecutor executor,
                                 CostReconciliationTracking tracking, CostReconciliationProducer producer,
                                 CostReconciliationControlService controls) {
        this.executor = executor;
        this.tracking = tracking;
        this.producer = producer;
        this.controls = controls;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        ready = true;
        submit("BOOT:" + bootId, false);
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Shanghai", scheduler = "costReconciliationTaskScheduler")
    public void daily() {
        submit("DAILY:" + LocalDate.now(ZoneId.of("Asia/Shanghai")), true);
    }

    private void submit(String trigger, boolean daily) {
        log.info("[reconciliation] 扫描触发 trigger={} source={}", trigger, daily ? "DAILY" : "BOOT");
        AtomicBoolean running = daily ? dailyRunning : bootRunning;
        if (!ready) {
            log.info("[reconciliation] 扫描跳过 trigger={} reason=APPLICATION_NOT_READY", trigger);
            return;
        }
        if (!processingEnabled) {
            log.info("[reconciliation] 扫描跳过 trigger={} reason=PROCESSING_DISABLED", trigger);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[reconciliation] 扫描跳过 trigger={} reason=ALREADY_RUNNING", trigger);
            return;
        }
        try {
            executor.execute(() -> run(trigger, daily, running));
        } catch (TaskRejectedException busy) {
            running.set(false);
            log.warn("[reconciliation] 发现资源占满 trigger={}", trigger);
        }
    }

    private void run(String trigger, boolean daily, AtomicBoolean running) {
        try {
            if (tracking.enabled()) {
                controls.recoverAtLegalTrigger();
                producer.discover(trigger, daily);
            } else {
                log.info("[reconciliation] 扫描跳过 trigger={} reason=CAPTURE_DISABLED", trigger);
            }
        } catch (RuntimeException failure) {
            log.error("[reconciliation] 合法发现触发失败 trigger={}", trigger, failure);
        } finally {
            running.set(false);
        }
    }
}
