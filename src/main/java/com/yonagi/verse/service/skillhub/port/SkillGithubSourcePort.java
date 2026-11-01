package com.yonagi.verse.service.skillhub.port;

import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import com.yonagi.verse.service.skillhub.SkillGithubSourceRequest;
import java.nio.file.Path;
import java.util.List;

/** 公开GitHub只读边界，不接受用户认证头或任意URL。 */
public interface SkillGithubSourcePort {
    /** @return 已验证公开仓库中完整的有界引用选项，不自动选择歧义来源 */
    List<SkillGithubSourceOptionDTO> resolve(SkillGithubSourceRequest request, SkillImportBudget budget);

    /** @return 固定提交tree的有界原始JSON；截断必须拒绝 */
    byte[] tree(SkillGithubSourceOptionDTO source, SkillImportBudget budget);

    /** 将固定提交归档流式写入受控本地文件，不接受浮动ref。 */
    void download(SkillGithubSourceOptionDTO source, Path target, SkillImportBudget budget);
}
