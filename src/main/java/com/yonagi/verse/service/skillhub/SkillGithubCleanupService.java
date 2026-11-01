package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yonagi.verse.dao.entity.skillhub.SkillGithubSourceDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillGithubSourceMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GitHub源清理许可和逐对象CAS确认，不在数据库事务中删除S3对象。 */
@Service
@RequiredArgsConstructor
public class SkillGithubCleanupService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final SkillImportTaskMapper tasks;
    private final SkillGithubSourceMapper sources;
    private final SkillDraftMapper drafts;
    private final AccountAvailabilityService accounts;
    private final SkillGithubSnapshotCodec codec;

    /** 等待过期不能被读取或旧确认请求续期。 */
    @Transactional(rollbackFor = Exception.class)
    public void expire(Long jobId) {
        SkillImportTaskDO task = tasks.selectByIdForUpdate(jobId);
        LocalDateTime now = now();
        if (task != null && Set.of("WAITING_CONFIRMATION", "WAITING_SELECTION").contains(task.getStatus())
                && task.getWaitUntil() != null && !task.getWaitUntil().isAfter(now)) {
            task.setStatus("FAILED");
            task.setErrorCode("SOURCE_EXPIRED");
            task.setPublicReason("来源或目录选择已过期，请重新导入固定提交");
            task.setUpdateTime(now);
            tasks.updateById(task);
        }
    }

    /** @return 已终态且到期的单个源对象Key集合；任务不可复活，不会与新代次绑定竞争 */
    @Transactional(rollbackFor = Exception.class)
    public List<String> keys(Long jobId) {
        SkillImportTaskDO task = tasks.selectByIdForUpdate(jobId);
        if (task == null || !TERMINAL.contains(task.getStatus()) || !"GITHUB".equals(task.getSourceType())) {
            return List.of();
        }
        boolean ended = !accounts.isAccountActive(task.getOwnerUserId());
        com.yonagi.verse.dao.entity.skillhub.SkillDraftDO draft = drafts.selectById(task.getDraftId());
        ended = ended || draft == null || Set.of("CANCELLED", "EXPIRED").contains(draft.getStatus());
        if (!ended && task.getUpdateTime().isAfter(now().minusDays(7))) {
            return List.of();
        }
        SkillGithubSourceDO source = sources.selectById(jobId);
        List<String> keys = new ArrayList<>();
        if (task.getSourceKey() != null) {
            keys.add(task.getSourceKey());
        }
        if (source != null && source.getTreeKey() != null) {
            keys.add(source.getTreeKey());
        }
        return List.copyOf(keys);
    }

    /** 每个对象实际删除成功后才清除其引用，另一个对象失败仍可恢复。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleted(Long jobId, String key) {
        SkillImportTaskDO task = tasks.selectByIdForUpdate(jobId);
        if (task == null || !TERMINAL.contains(task.getStatus())) {
            return;
        }
        tasks.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getJobId, jobId).eq(SkillImportTaskDO::getSourceKey, key)
                .set(SkillImportTaskDO::getSourceKey, null).set(SkillImportTaskDO::getSourceSha256, null)
                .set(SkillImportTaskDO::getSourceBytes, null));
        sources.update(null, Wrappers.lambdaUpdate(SkillGithubSourceDO.class)
                .eq(SkillGithubSourceDO::getJobId, jobId).eq(SkillGithubSourceDO::getTreeKey, key)
                .set(SkillGithubSourceDO::getTreeKey, null).set(SkillGithubSourceDO::getTreeSha256, null));
    }

    /** 源字节清理后抹除大字段，安全问题与固定来源保留到明确恢复期限。 */
    @Transactional(rollbackFor = Exception.class)
    public void prune(Long jobId) {
        SkillImportTaskDO task = tasks.selectByIdForUpdate(jobId);
        SkillGithubSourceDO source = sources.selectById(jobId);
        if (task == null || source == null || !TERMINAL.contains(task.getStatus())
                || task.getSourceKey() != null || source.getTreeKey() != null) {
            return;
        }
        com.yonagi.verse.dao.entity.skillhub.SkillDraftDO draft = drafts.selectById(task.getDraftId());
        boolean remove = !accounts.isAccountActive(task.getOwnerUserId()) || draft == null
                || Set.of("CANCELLED", "EXPIRED").contains(draft.getStatus())
                || !task.getUpdateTime().isAfter(now().minusDays(30));
        if (remove) {
            sources.deleteById(jobId);
        } else {
            // 保留原Task视图已派生的最终来源，不重新解析引用；清候选不能使重试退回扫描父目录。
            source.setConfirmedSourceJson(retainedSource(source));
            sources.update(null, Wrappers.lambdaUpdate(SkillGithubSourceDO.class)
                    .eq(SkillGithubSourceDO::getJobId, jobId).set(SkillGithubSourceDO::getRequestJson, null)
                    .set(SkillGithubSourceDO::getSourceOptionsJson, null).set(SkillGithubSourceDO::getCandidatesJson, null)
                    .set(SkillGithubSourceDO::getConfirmedSourceJson, source.getConfirmedSourceJson())
                    .set(SkillGithubSourceDO::getUpdateTime, now()));
        }
    }

    private String retainedSource(SkillGithubSourceDO source) {
        if (source.getSelectedCandidateId() == null || source.getCandidatesJson() == null) {
            return source.getConfirmedSourceJson();
        }
        SkillGithubCandidateDTO selected = codec.list(source.getCandidatesJson(),
                        new TypeReference<List<SkillGithubCandidateDTO>>() { }).stream()
                .filter(value -> source.getSelectedCandidateId().equals(value.candidateId())).findFirst().orElse(null);
        SkillGithubSourceOptionDTO confirmed = codec.decode(source.getConfirmedSourceJson(), SkillGithubSourceOptionDTO.class);
        if (selected == null || confirmed == null || confirmed.source() == null) {
            return source.getConfirmedSourceJson();
        }
        SkillSourceDTO fixed = confirmed.source();
        SkillSourceDTO retained = new SkillSourceDTO(fixed.schemaVersion(), fixed.type(), fixed.repositoryUrl(),
                fixed.repositoryId(), fixed.requestedRef(), fixed.resolvedRefType(), fixed.commitSha(), selected.directory());
        return codec.encode(new SkillGithubSourceOptionDTO(confirmed.sourceOptionId(), retained, confirmed.treeSha()), 8192);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
