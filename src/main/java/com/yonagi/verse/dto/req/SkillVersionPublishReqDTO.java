package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** 确认后固定的发布意图，结果未知时原样重试。 */
public record SkillVersionPublishReqDTO(
        /** 已确认编辑区修订。 */
        @NotNull @PositiveOrZero Long workspaceRevision,
        /** 开始准备时的Skill修订。 */
        @NotNull @PositiveOrZero Long expectedSkillRevision,
        /** 发布类型。 */
        @NotNull @Pattern(regexp = "DRAFT|RELEASE") String type,
        /** 服务端预览摘要。 */
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest) {}
