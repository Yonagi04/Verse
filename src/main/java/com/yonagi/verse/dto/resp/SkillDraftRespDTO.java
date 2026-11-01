package com.yonagi.verse.dto.resp;

import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import java.time.OffsetDateTime;

/** 仅本人可读的草稿及检查预览，不含对象Key。 */
public record SkillDraftRespDTO(
        /** 草稿标识。 */ String draftId,
        /** 预留Skill标识。 */ String skillId,
        /** 固定公开slug。 */ String slug,
        /** 草稿业务状态。 */ String status,
        /** 编辑与提交修订。 */ Long revision,
        /** 当前导入任务。 */ String jobId,
        /** 本人的待公开表单。 */ SkillPublicationMetadataDTO metadata,
        /** 包内name。 */ String packageName,
        /** 包内description。 */ String packageDescription,
        /** 绑定快照的SKILL.md预览。 */ String skillMarkdown,
        /** 规范化ZIP大小。 */ Long archiveBytes,
        /** 已检查文件数。 */ Integer fileCount,
        /** 修改时间。 */ OffsetDateTime updatedAt,
        /** 当前已检查制品标识。 */ String artifactId,
        /** 当前文件内容摘要。 */ String contentSha256,
        /** 不可变获取来源。 */ SkillSourceDTO source) {
    /** 兼容既有ZIP调用方，完整生产视图使用附带快照标识的构造器。 */
    public SkillDraftRespDTO(String draftId, String skillId, String slug, String status, Long revision, String jobId,
                             SkillPublicationMetadataDTO metadata, String packageName, String packageDescription,
                             String skillMarkdown, Long archiveBytes, Integer fileCount, OffsetDateTime updatedAt) {
        this(draftId, skillId, slug, status, revision, jobId, metadata, packageName, packageDescription,
                skillMarkdown, archiveBytes, fileCount, updatedAt, null, null, null);
    }
    @Override
    public String toString() {
        return "SkillDraftRespDTO[draftId=" + draftId + ",status=" + status + ",revision=" + revision + "]";
    }
}
