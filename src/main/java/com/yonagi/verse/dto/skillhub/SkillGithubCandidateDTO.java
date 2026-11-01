package com.yonagi.verse.dto.skillhub;

import java.util.List;

/** 候选初检只决定能否选择，不能冒充完整内容检查通过。 */
public record SkillGithubCandidateDTO(
        /** 绑定本次扫描摘要的候选标识。 */ String candidateId,
        /** 仓库中的Skill根目录。 */ String directory,
        /** 包内声明名称。 */ String packageName,
        /** 有界简介预览。 */ String packageDescription,
        /** 子树普通文件数。 */ Integer fileCount,
        /** 子树实际文件总量。 */ Long unpackedBytes,
        /** 是否允许进入完整检查。 */ Boolean selectable,
        /** 最多首个安全初检原因。 */ List<SkillValidationIssueDTO> issues) {
}
