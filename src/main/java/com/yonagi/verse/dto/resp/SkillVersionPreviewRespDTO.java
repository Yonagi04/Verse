package com.yonagi.verse.dto.resp;

import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

/** 发版前供用户确认的固定内容。 */
public record SkillVersionPreviewRespDTO(
        /** 发布类型。 */
        String type,
        /** 候选平台版本。 */
        String version,
        /** 当前编辑修订。 */
        String workspaceRevision,
        /** 当前Skill修订。 */
        String expectedSkillRevision,
        /** 确认摘要。 */
        String confirmationDigest,
        /** 比较基准。 */
        String baseVersionId,
        /** 比较基准版本号。 */
        String baseVersion,
        /** 新增数。 */
        Integer addedCount,
        /** 修改数。 */
        Integer modifiedCount,
        /** 删除数。 */
        Integer deletedCount,
        /** 文件总数。 */
        Integer fileCount,
        /** 内容摘要。 */
        String contentSha256,
        /** 实际来源。 */
        SkillSourceDTO source,
        /** 来源草稿编号。 */
        String sourceDraftVersion,
        /** 当前公开版本。 */
        String currentVersion,
        /** 本次更新说明。 */
        String releaseNotes) {}
