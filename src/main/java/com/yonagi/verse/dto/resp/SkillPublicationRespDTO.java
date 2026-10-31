package com.yonagi.verse.dto.resp;

/** 同事务提交后的稳定回执，原key重试返回相同单据和版本。 */
public record SkillPublicationRespDTO(
        /** Skill标识。 */ String skillId,
        /** 冻结提交标识。 */ String submissionId,
        /** 审批单据标识。 */ String approvalId,
        /** 不可变发布版本。 */ String versionId,
        /** 实际审批状态。 */ String approvalStatus,
        /** 实际决定来源，不暗示人工审核。 */ String decisionSource,
        /** 实际发布结果。 */ String publicationStatus,
        /** 稳定公开作者标识。 */ String publisher,
        /** 稳定公开slug。 */ String slug) {
}
