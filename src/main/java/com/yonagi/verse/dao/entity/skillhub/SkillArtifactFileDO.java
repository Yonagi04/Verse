package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 不可变制品内的文件清单，不承担文件访问授权。 */
@Getter
@Setter
@TableName("t_skill_file")
public class SkillArtifactFileDO {
    /** 快照文件索引标识。 */
    @TableId(type = IdType.INPUT)
    private Long fileId;
    /** 所属不可变快照。 */
    private Long artifactId;
    /** 快照内规范化相对路径。 */
    private String relativePath;
    /** 完整相对路径摘要。 */
    private String pathHash;
    /** 文件字节摘要。 */
    private String sha256;
    /** 文件字节数。 */
    private Long sizeBytes;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
