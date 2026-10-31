package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillPackageInspector;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次导入应用用例：协调私有存储、隔离检查与DB绑定，不承担周期调度。 */
@Service
@Slf4j
public class SkillImportProcessingService {
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);
    private final SkillImportExecutionService executionService;
    private final SkillArtifactStore artifactStore;
    private final SkillPackageInspector inspector;
    private final SkillValidationReportPolicy reportPolicy;
    private final ThreadPoolTaskScheduler scheduler;

    /** 单次任务使用有界解析执行及独立续租调度，不在数据库事务中执行远程调用。 */
    public SkillImportProcessingService(SkillImportExecutionService executionService, SkillArtifactStore artifactStore,
                                        SkillPackageInspector inspector, SkillValidationReportPolicy reportPolicy,
                                        @Qualifier("skillLeaseScheduler") ThreadPoolTaskScheduler scheduler) {
        this.executionService = executionService;
        this.artifactStore = artifactStore;
        this.inspector = inspector;
        this.reportPolicy = reportPolicy;
        this.scheduler = scheduler;
    }

    /**
     * @param jobId DB候选任务；领取失败时不做解析，内容拒绝与依赖失败采用不同终态/重试语义
     */
    public void process(Long jobId) {
        long started = System.nanoTime();
        SkillExecutionClaim claim = null;
        ScheduledFuture<?> heartbeat = null;
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        try {
            claim = executionService.claim(jobId);
            if (claim == null) {
                return;
            }
            SkillExecutionClaim current = claim;
            heartbeat = scheduler.scheduleAtFixedRate(() -> renew(current, leaseValid), HEARTBEAT);
            byte[] source = artifactStore.get(claim.sourceKey(), (int) SkillPackageLimitConstant.MAX_ARCHIVE_BYTES);
            if (source.length != claim.sourceBytes() || !DigestUtil.sha256Hex(source).equals(claim.sourceSha256())
                    || !leaseValid.get()) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
            SkillValidationReport report = reportPolicy.validate(inspector.inspect(source, leaseValid::get));
            if (!leaseValid.get() || Thread.currentThread().isInterrupted()
                    || !DigestUtil.sha256Hex(source).equals(report.sourceSha256())) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
            if (!report.success()) {
                executionService.fail(claim, true, report.code(), reportPolicy.reason(report.code()));
                return;
            }
            if (!leaseValid.get()) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
            String archiveKey = "skillhub/v1/artifact/" + claim.jobId() + "/" + claim.token() + ".zip";
            artifactStore.put(archiveKey, report.archive());
            executionService.complete(claim, report, archiveKey);
            log.info("Skill检查完成: jobId={}, files={}, archiveBytes={}, unpackedBytes={}, elapsedMillis={}",
                    jobId, report.files().size(), report.archive().length, report.unpackedBytes(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (RuntimeException exception) {
            log.warn("Skill检查执行失败: jobId={}, type={}", jobId, exception.getClass().getSimpleName());
            if (claim != null) {
                try {
                    executionService.fail(claim, false, "CHECK_UNAVAILABLE", "检查依赖暂时不可用，正在有界重试");
                } catch (RuntimeException persistenceFailure) {
                    log.warn("Skill检查失败状态未提交，等待租约恢复: jobId={}, type={}", jobId,
                            persistenceFailure.getClass().getSimpleName());
                }
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private void renew(SkillExecutionClaim claim, AtomicBoolean valid) {
        if (!valid.get()) {
            return;
        }
        try {
            if (!executionService.renew(claim)) {
                valid.set(false);
            }
        } catch (RuntimeException exception) {
            valid.set(false);
            log.warn("Skill执行租约不可确认: jobId={}, type={}", claim.jobId(), exception.getClass().getSimpleName());
        }
    }
}
