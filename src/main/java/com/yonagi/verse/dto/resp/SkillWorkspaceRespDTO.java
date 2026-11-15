package com.yonagi.verse.dto.resp;

import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

/** 本人的唯一待发布区，大整数统一用字符串返回。 */
public record SkillWorkspaceRespDTO(
        /** Skill标识。 */
        String skillId,
        /** 唯一编辑区标识。 */
        String draftId,
        /** 公开稳定标识。 */
        String slug,
        /** Skill当前状态。 */
        String skillStatus,
        /** 当前Skill修订。 */
        String skillRevision,
        /** 准备时修订，不随读取刷新。 */
        String baseSkillRevision,
        /** 当前编辑修订。 */
        String revision,
        /** 编辑状态。 */
        String status,
        /** 最新准备任务。 */
        String jobId,
        /** 当前制品。 */
        String artifactId,
        /** 当前内容摘要。 */
        String contentSha256,
        /** 采用的草稿节点。 */
        String sourceVersionId,
        /** 本次更新说明。 */
        String releaseNotes,
        /** 沿用的基础信息，只读。 */
        SkillPublicationMetadataDTO metadata,
        /** 固定创建来源。 */
        SkillSourceDTO creationSource,
        /** 本次实际获取来源。 */
        SkillSourceDTO source,
        /** 当前正式平台版本。 */
        String currentVersion,
        /** 最近正式发布时间。 */ java.time.OffsetDateTime currentReleaseAt,
        /** 服务端给出的下一正式编号。 */ String nextReleaseVersion,
        /** 本轮最新草稿编号。 */ String latestDraftVersion,
        /** 本轮最新草稿时间。 */ java.time.OffsetDateTime latestDraftAt,
        /** 最新草稿可转正的页面提示。 */ Boolean canPromote) {}
