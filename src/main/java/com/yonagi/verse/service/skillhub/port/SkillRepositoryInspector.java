package com.yonagi.verse.service.skillhub.port;

import com.yonagi.verse.dto.skillhub.SkillGithubCandidateDTO;
import java.nio.file.Path;
import java.util.List;

/** 仓库快照解析与选定子树提取边界，不暴露第三方ZIP类型。 */
public interface SkillRepositoryInspector {
    /** @return 全部有界初检候选，不跳过不合法匹配 */
    List<SkillGithubCandidateDTO> discover(Path archive, byte[] tree, String directory, String scanDigest,
                                          SkillImportBudget budget);

    /** @return 文件集合和字节已与固定Git tree核对的单Skill输入ZIP */
    byte[] extract(Path archive, byte[] tree, SkillGithubCandidateDTO candidate, SkillImportBudget budget);
}
