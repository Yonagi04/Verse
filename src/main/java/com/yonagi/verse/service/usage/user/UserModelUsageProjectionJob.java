package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.common.config.UserModelUsageProperties;
import com.yonagi.verse.dao.mapper.UserModelUsageProjectionMapper;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class UserModelUsageProjectionJob {
    private final UserModelUsageProperties properties;
    private final UserModelUsageProjectionMapper work;
    private final UserModelUsageProjectionService projection;
    private final BudgetPeriodResolver time;
    private final MeterRegistry metrics;

    @Scheduled(fixedDelayString="${verse.personal-usage.poll-delay-ms:2000}")
    public void project() {
        if (!properties.isWorkerEnabled() || !properties.isProjectionEnabled()) return;
        for (var row:work.due(time.local(time.now()))) {
            try { projection.project(row.getUserId(),row.getUsageId()); }
            catch (RuntimeException failure) {
                metrics.counter("personal.usage.failed").increment();
                log.error("[personal-usage] 投影待重试 usageId={}, failureType={}",row.getUsageId(),failure.getClass().getSimpleName());
                try { projection.failed(row.getUserId(),row.getUsageId(),"PROJECTION_FAILED"); }
                catch (RuntimeException recording) { log.error("[personal-usage] 重试状态未写入 usageId={}",row.getUsageId()); }
            }
        }
    }
    @Scheduled(fixedDelayString="${verse.personal-usage.repair-delay-ms:30000}")
    public void repair() {
        if (!properties.isProjectionEnabled()) return;
        for (var usage:work.missingWork()) {
            try { projection.repair(usage.getUserId(),usage.getId()); }
            catch (RuntimeException failure) { log.warn("[personal-usage] 补工作项失败 usageId={}, failureType={}",usage.getId(),failure.getClass().getSimpleName()); }
        }
    }
}
