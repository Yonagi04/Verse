package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** 显式开始下一轮准备，不接受客户端指定来源或旧草稿。 */
public record SkillWorkspaceOpenReqDTO(
        /** 新文件或本轮最新草稿。 */
        @NotNull @Pattern(regexp = "NEW_CONTENT|LATEST_DRAFT") String mode,
        /** 用户看到的Skill修订。 */
        @NotNull @PositiveOrZero Long expectedSkillRevision) {}
