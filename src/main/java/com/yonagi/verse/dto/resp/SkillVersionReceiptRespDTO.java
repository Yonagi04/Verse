package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 只描述本次成功事实，重放不受当前公开版本变化影响。 */
public record SkillVersionReceiptRespDTO(
        /** 稳定Skill标识。 */
        String skillId,
        /** 不可变版本标识。 */
        String versionId,
        /** DRAFT或RELEASE。 */
        String type,
        /** 平台版本号。 */
        String version,
        /** 稳定发布顺序。 */
        String sequence,
        /** 真实发布时间。 */
        OffsetDateTime publishedAt,
        /** 转正所采用的草稿标识。 */
        String sourceDraftVersionId,
        /** 正式提交标识，草稿为空。 */
        String submissionId,
        /** 正式审批标识，草稿为空。 */
        String approvalId) {}
