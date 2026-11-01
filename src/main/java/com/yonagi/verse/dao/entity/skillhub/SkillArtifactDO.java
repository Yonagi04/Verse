package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 不可变私有制品的索引、摘要和存储生命周期。 */
@Getter
@Setter
@TableName("t_skill_artifact")
public class SkillArtifactDO {
    /** 不可变文件快照标识。 */
    @TableId(type = IdType.INPUT)
    private Long artifactId;
    /** 快照所属账号。 */
    private Long ownerUserId;
    /** ZIP或GITHUB，权威来源不取作者自填URL。 */
    private String sourceType;
    /** 不可变获取来源，GitHub包含仓库、目录和完整SHA。 */
    private String sourceJson;
    /** 私有归档对象Key。 */
    private String archiveKey;
    /** 下载ZIP字节SHA256。 */
    private String archiveSha256;
    /** 规范化路径与文件摘要的内容摘要。 */
    private String contentSha256;
    /** 下载包字节数。 */
    private Long archiveBytes;
    /** 实际展开文件总字节数。 */
    private Long unpackedBytes;
    /** 实际文件数量。 */
    private Integer fileCount;
    /** 同快照内的SKILL.md原文。 */
    private String skillMarkdown;
    /** 有界不可变文件清单与检查摘要。 */
    private String manifestJson;
    /** READY、DELETE_PENDING或DELETED。 */
    private String status;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
