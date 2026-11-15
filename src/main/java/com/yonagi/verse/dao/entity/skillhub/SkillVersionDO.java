package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 已批准的不可变内容版本及作者声明。 */
@Getter
@Setter
@TableName("t_skill_version")
public class SkillVersionDO {
    /** 不可变已批准版本标识。 */
    @TableId(type = IdType.INPUT)
    private Long versionId;
    /** 所属Skill。 */
    private Long skillId;
    /** 平台SemVer且不可覆盖。 */
    private String versionLabel;

    /** DRAFT私人草稿或RELEASE正式版。 */
    private String versionType;

    /** 正式发布轮次，从1开始。 */
    private Integer releaseNumber;

    /** 草稿序号，正式版为0。 */
    private Integer draftNumber;

    /** 同Skill内稳定发布顺序。 */
    private Long sequenceNumber;

    /** 当时沿用的基础信息快照。 */
    private Long displayRevisionId;

    /** 正式发布所采用的草稿，直接发布为空。 */
    private Long sourceDraftVersionId;

    /** 固定的文件比较基准，首版为空。 */
    private Long diffBaseVersionId;

    /** 新增文件数，历史无法恢复时为空。 */
    private Integer addedCount;

    /** 修改文件数，历史无法恢复时为空。 */
    private Integer modifiedCount;

    /** 删除文件数，历史无法恢复时为空。 */
    private Integer deletedCount;

    /** 实际发布账号。 */
    private Long publishedBy;

    /** 随版本长期保留的发布UUID。 */
    private String publishKey;

    /** 固定发布请求摘要。 */
    private String publishRequestHash;

    /** 不可变制品。 */
    private Long artifactId;
    /** 批准并完成发布的提交。 */
    private Long approvedSubmissionId;
    /** AVAILABLE或BLOCKED。 */
    private String status;
    /** 首版更新说明。 */
    private String releaseNotes;
    /** 冻结的许可来源依赖声明。 */
    private String declarationsJson;
    /** 批准发布时间。 */
    private LocalDateTime publishedAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
