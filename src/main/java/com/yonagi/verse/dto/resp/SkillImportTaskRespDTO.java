package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;
import java.util.List;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.dto.skillhub.SkillValidationIssueDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubInputDTO;

/** 持久化任务的安全状态，不包含源Key或执行令牌。 */
public record SkillImportTaskRespDTO(
        /** 任务标识。 */ String jobId,
        /** 本人草稿标识。 */ String draftId,
        /** 当前任务状态。 */ String status,
        /** 创建任务后的草稿修订。 */ Long draftRevision,
        /** 固定机器错误原因。 */ String errorCode,
        /** 不含秘密的检查说明。 */ String reason,
        /** 已领取执行次数。 */ Integer attempts,
        /** 最近变化时间。 */ OffsetDateTime updatedAt,
        /** ZIP或GITHUB。 */ String sourceType,
        /** 当前执行阶段。 */ String phase,
        /** 仅本人可见的来源解析选项。 */ List<SkillGithubSourceOptionDTO> sourceOptions,
        /** 服务端固定来源。 */ SkillSourceDTO source,
        /** 扫描候选总量。 */ Integer candidateCount,
        /** 当前扫描摘要。 */ String scanDigest,
        /** 等待用户截止。 */ OffsetDateTime waitUntil,
        /** 是否可以人工新建任务重试固定来源。 */ Boolean retryable,
        /** 建议重试时间，UTC。 */ OffsetDateTime retryAt,
        /** 有界安全文件问题。 */ List<SkillValidationIssueDTO> issues,
        /** 本人可恢复的来源输入。 */ SkillGithubInputDTO requestedSource) {
    /** 旧ZIP调用保持原形状，新增字段使用明确的ZIP语义。 */
    public SkillImportTaskRespDTO(String jobId, String draftId, String status, Long draftRevision, String errorCode,
                                  String reason, Integer attempts, OffsetDateTime updatedAt) {
        this(jobId, draftId, status, draftRevision, errorCode, reason, attempts, updatedAt, "ZIP", "VALIDATE",
                List.of(), SkillSourceDTO.zip(), 0, null, null, false, null, List.of(), null);
    }
}
