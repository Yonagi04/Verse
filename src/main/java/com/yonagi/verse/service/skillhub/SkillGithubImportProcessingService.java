package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yonagi.verse.service.skillhub.port.SkillRepositoryInspector;
import com.yonagi.verse.common.constant.SkillGithubLimitConstant;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourceException;
import com.yonagi.verse.service.skillhub.port.SkillGithubSourcePort;
import com.yonagi.verse.service.skillhub.port.SkillImportBudget;
import com.yonagi.verse.service.skillhub.port.SkillPackageInspector;
import com.yonagi.verse.service.skillhub.port.SkillValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

/** 固定SHA获取/快照检查用例；全部远程和CPU处理位于数据库短事务之外。 */
@Service
@Slf4j
public class SkillGithubImportProcessingService {
    private final SkillImportExecutionService execution;
    private final SkillGithubSourcePort github;
    private final SkillGithubSourceMapper sources;
    private final SkillImportTaskMapper tasks;
    private final SkillGithubSnapshotCodec codec;
    private final SkillArtifactStore store;
    private final SkillRepositoryInspector snapshots;
    private final SkillPackageInspector inspector;
    private final SkillValidationReportPolicy reports;
    private final SkillImportTemporaryFiles temporary;
    private final ThreadPoolTaskScheduler scheduler;

    public SkillGithubImportProcessingService(SkillImportExecutionService execution, SkillGithubSourcePort github,
            SkillGithubSourceMapper sources, SkillImportTaskMapper tasks, SkillGithubSnapshotCodec codec,
            SkillArtifactStore store, SkillRepositoryInspector snapshots, SkillPackageInspector inspector,
            SkillValidationReportPolicy reports, SkillImportTemporaryFiles temporary,
            @Qualifier("skillLeaseScheduler") ThreadPoolTaskScheduler scheduler) {
        this.execution = execution;
        this.github = github;
        this.sources = sources;
        this.tasks = tasks;
        this.codec = codec;
        this.store = store;
        this.snapshots = snapshots;
        this.inspector = inspector;
        this.reports = reports;
        this.temporary = temporary;
        this.scheduler = scheduler;
    }

    /** 专用获取Worker与最终检查Worker均调用此完整用例，不共享请求上下文。 */
    public void process(Long jobId) {
        SkillExecutionClaim claim = null;
        ScheduledFuture<?> heartbeat = null;
        AtomicBoolean valid = new AtomicBoolean(true);
        try {
            claim = execution.claim(jobId);
            if (claim == null) {
                return;
            }
            if (!"GITHUB".equals(claim.sourceType())) {
                throw new IllegalStateException("GitHub工作槽位不能领取ZIP");
            }
            SkillExecutionClaim active = claim;
            heartbeat = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!execution.renew(active)) {
                        valid.set(false);
                    }
                } catch (RuntimeException exception) {
                    valid.set(false);
                }
            }, Duration.ofSeconds("RESOLVE".equals(claim.phase()) ? 1 : 10));
            SkillImportBudget budget = new SkillImportBudget(claim.deadline(), valid::get);
            SkillGithubSourceDO source = sources.selectById(jobId);
            if (source == null) {
                throw rejected("INVALID_SOURCE", "来源记录不完整，请重新导入");
            }
            if ("RESOLVE".equals(claim.phase())) {
                List<SkillGithubSourceOptionDTO> options = github.resolve(
                        codec.decode(source.getRequestJson(), SkillGithubSourceRequest.class), budget);
                budget.check();
                execution.resolved(claim, options);
            } else {
                inspectSnapshot(claim, source, budget);
            }
        } catch (RuntimeException exception) {
            log.warn("GitHub导入阶段失败: jobId={}, phase={}, type={}", jobId,
                    claim == null ? "UNCLAIMED" : claim.phase(), exception.getClass().getSimpleName());
            if (claim != null) {
                SkillGithubSourceException failure = exception instanceof SkillGithubSourceException sourceFailure
                        ? sourceFailure : new SkillGithubSourceException("SOURCE_UNAVAILABLE",
                        "导入依赖暂时不可用，正在有界重试", true, null);
                try {
                    execution.githubFailed(claim, failure);
                } catch (RuntimeException persistenceFailure) {
                    log.warn("GitHub失败状态未提交，等待租约恢复: jobId={}, type={}", jobId,
                            persistenceFailure.getClass().getSimpleName());
                }
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private void inspectSnapshot(SkillExecutionClaim claim, SkillGithubSourceDO source, SkillImportBudget budget) {
        Path archive = null;
        Path treeFile = null;
        try {
            SkillGithubSourceOptionDTO confirmed = codec.decode(source.getConfirmedSourceJson(),
                    SkillGithubSourceOptionDTO.class);
            if (confirmed == null) {
                throw rejected("INVALID_SOURCE", "请先确认固定来源");
            }
            archive = temporary.create(SkillGithubLimitConstant.MAX_ARCHIVE_BYTES);
            treeFile = temporary.create(SkillGithubLimitConstant.MAX_TREE_BYTES);
            SkillImportTaskDO storedTask = tasks.selectById(claim.jobId());
            boolean saved = storedTask.getSourceKey() != null && source.getTreeKey() != null;
            byte[] tree;
            List<SkillGithubCandidateDTO> candidates;
            if (saved) {
                store.downloadSource(storedTask.getSourceKey(), archive, SkillGithubLimitConstant.MAX_ARCHIVE_BYTES, budget);
                store.downloadSource(source.getTreeKey(), treeFile, SkillGithubLimitConstant.MAX_TREE_BYTES, budget);
                if (Files.size(archive) != storedTask.getSourceBytes()
                        || !hash(archive, budget).equals(storedTask.getSourceSha256())
                        || !hash(treeFile, budget).equals(source.getTreeSha256())) {
                    throw rejected("SOURCE_INTEGRITY_FAILED", "已保存仓库快照缺失或摘要不匹配");
                }
                tree = Files.readAllBytes(treeFile);
                candidates = codec.list(source.getCandidatesJson(), new TypeReference<List<SkillGithubCandidateDTO>>() { });
            } else {
                if (!"FETCH".equals(claim.phase())) {
                    throw rejected("SOURCE_EXPIRED", "源快照已不可用，请重新导入固定提交");
                }
                tree = github.tree(confirmed, budget);
                Files.write(treeFile, tree);
                github.download(confirmed, archive, budget);
                String sourceSha = hash(archive, budget);
                String treeSha = DigestUtil.sha256Hex(tree);
                String scan = DigestUtil.sha256Hex("GITHUB_SCAN_V1\n" + confirmed.source().commitSha() + "\n"
                        + confirmed.source().directory() + "\n" + sourceSha + "\n" + treeSha);
                candidates = snapshots.discover(archive, tree, confirmed.source().directory(), scan, budget);
                String keyBase = "skillhub/v1/source/" + claim.jobId() + "/" + claim.token();
                String archiveKey = keyBase + ".zip";
                String treeKey = keyBase + ".json";
                store.putSource(archiveKey, archive, "application/zip", SkillGithubLimitConstant.MAX_ARCHIVE_BYTES, budget);
                store.putSource(treeKey, treeFile, "application/json", SkillGithubLimitConstant.MAX_TREE_BYTES, budget);
                budget.check();
                if (!execution.fetched(claim, archiveKey, sourceSha, Files.size(archive), treeKey, treeSha, scan, candidates)) {
                    return;
                }
                source = sources.selectById(claim.jobId());
            }
            String candidateId = source.getSelectedCandidateId();
            SkillGithubCandidateDTO selected = candidates.stream().filter(value -> value.candidateId().equals(candidateId))
                    .findFirst().orElseThrow(() -> rejected("INVALID_SOURCE", "请先选择一个Skill目录"));
            byte[] selectedInput = snapshots.extract(archive, tree, selected, budget);
            String selectedInputSha256 = DigestUtil.sha256Hex(selectedInput);
            SkillValidationReport report = reports.validate(inspector.inspect(selectedInput, () -> {
                budget.check();
                return true;
            }));
            budget.check();
            if (!selectedInputSha256.equals(report.sourceSha256())) {
                throw rejected("SOURCE_INTEGRITY_FAILED", "所选目录输入与检查报告不一致");
            }
            if (!report.success()) {
                throw new SkillGithubSourceException(report.code(), reports.reason(report.code()), false, null,
                        report.issues());
            }
            String artifactKey = "skillhub/v1/artifact/" + claim.jobId() + "/" + claim.token() + ".zip";
            store.put(artifactKey, report.archive());
            budget.check();
            execution.complete(claim, report, artifactKey);
            log.info("GitHub导入检查完成: jobId={}, files={}, archiveBytes={}", claim.jobId(), report.files().size(),
                    report.archive().length);
        } catch (IOException exception) {
            throw new SkillGithubSourceException("SOURCE_UNAVAILABLE", "临时源快照暂不可用", true, null);
        } finally {
            temporary.remove(archive);
            temporary.remove(treeFile);
        }
    }

    private String hash(Path path, SkillImportBudget budget) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                budget.check();
                digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java运行时缺少SHA256", exception);
        }
    }

    private SkillGithubSourceException rejected(String code, String message) {
        return new SkillGithubSourceException(code, message, false, null);
    }
}
