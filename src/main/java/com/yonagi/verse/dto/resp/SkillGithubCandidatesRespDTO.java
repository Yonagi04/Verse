package com.yonagi.verse.dto.resp;

import java.util.List;
import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;

/** 有界扫描快照的分页视图。 */
public record SkillGithubCandidatesRespDTO(
        /** 当前页，一基。 */ Integer page,
        /** 每页20或50项。 */ Integer size,
        /** 本次匹配总量。 */ Integer total,
        /** 本页初检候选。 */ List<SkillGithubCandidateDTO> items,
        /** 所选候选必须绑定此扫描摘要。 */ String scanDigest) {
}
