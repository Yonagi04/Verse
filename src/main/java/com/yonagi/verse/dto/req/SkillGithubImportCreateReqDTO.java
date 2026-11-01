package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 公开仓库导入；身份仅来自认证上下文。 */
public record SkillGithubImportCreateReqDTO(
        /** 当前草稿修订。 */ @NotNull @PositiveOrZero Long revision,
        /** 仓库根HTTPS地址或tree链接。 */ @NotBlank @Size(max = 2048) String repositoryUrl,
        /** DEFAULT、AUTO、BRANCH、TAG或COMMIT。 */ @Size(max = 16) String refType,
        /** 可选分支、Tag或完整SHA。 */ @Size(max = 255) String ref,
        /** 可选仓库相对扫描目录。 */ @Size(max = 512) String directory) {
}
