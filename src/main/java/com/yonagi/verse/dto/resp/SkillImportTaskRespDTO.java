package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 持久化任务的安全状态，不包含源Key或执行令牌。 */
public record SkillImportTaskRespDTO(
        /** 任务标识。 */ String jobId,
        /** 本人草稿标识。 */ String draftId,
        /** 当前任务状态。 */ String status,
        /** 创建任务后的草稿修订。 */ Long draftRevision,
        /** 固定机器错误原因。 */ String errorCode,
        /** 不含秘密的检查说明。 */ String reason,
        /** 已领取执行次数。 */ Integer attempts,
        /** 最近变化时间。 */ OffsetDateTime updatedAt) {
}
