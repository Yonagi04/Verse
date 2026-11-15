package com.yonagi.verse.dto.resp;

import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

import java.time.OffsetDateTime;

/** 本人历史节点；文件缺失不移除版本事实。 */
public record SkillVersionRespDTO(
        /** 节点标识。 */
        String versionId,
        /** DRAFT或RELEASE。 */
        String type,
        /** 平台版本号。 */
        String version,
        /** 稳定发布序列。 */
        String sequence,
        /** 节点可用状态。 */
        String status,
        /** 真实发布时间。 */
        OffsetDateTime publishedAt,
        /** 不可变说明。 */
        String releaseNotes,
        /** 实际获取来源，缺失时为空。 */
        SkillSourceDTO source,
        /** 固定基础信息，仅详情返回。 */
        SkillPublicationMetadataDTO metadata,
        /** 当前公开标志。 */
        Boolean currentRelease,
        /** 是否可以采用为最新草稿。 */
        Boolean canPublish,
        /** 来源草稿。 */
        String sourceDraftVersionId,
        /** 来源草稿平台号。 */ String sourceDraftVersion,
        /** 已用于发布的正式版本号。 */
        String usedByVersion,
        /** 比较基准。 */
        String baseVersionId,
        /** 比较基准号。 */
        String baseVersion,
        /** READY或UNAVAILABLE。 */
        String diffSummaryStatus,
        /** 新增数。 */
        Integer addedCount,
        /** 修改数。 */
        Integer modifiedCount,
        /** 删除数。 */
        Integer deletedCount,
        /** 文件是否可访问。 */
        Boolean filesAvailable) {}
