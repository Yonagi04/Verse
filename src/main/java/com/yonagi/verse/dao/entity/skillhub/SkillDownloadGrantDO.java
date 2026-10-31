package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 指定版本的短效下载能力，只保存凭据散列。 */
@Getter
@Setter
@TableName("t_skill_download_grant")
public class SkillDownloadGrantDO {
    /** 短效授权记录标识。 */
    @TableId(type = IdType.INPUT)
    private Long grantId;
    /** 仅保存能力凭据散列。 */
    private String tokenHash;
    /** 授权的固定版本。 */
    private Long versionId;
    /** 短效匿名主体散列，仅限流使用。 */
    private String subjectHash;
    /** 60秒授权截止时间。 */
    private LocalDateTime expiresAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
