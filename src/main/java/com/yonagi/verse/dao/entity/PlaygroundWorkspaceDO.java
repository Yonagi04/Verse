package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 二期个人对比分组或版本化预设。 */
@Data
@TableName("t_playground_workspace")
public class PlaygroundWorkspaceDO {
    /** 自增主键。 */ private Long id;
    /** 工作区业务 ID。 */ private Long workspaceId;
    /** 所属租户。 */ private Long tenantId;
    /** 创建者。 */ private Long ownerUserId;
    /** GROUP 或 PRESET。 */ private String kind;
    /** 名称。 */ private String title;
    /** 预设描述。 */ private String description;
    /** 白名单配置、历史前缀或预设版本。 */ private String payload;
    /** 乐观版本号。 */ private Integer revision;
    /** 组生成锁。 */ private Integer generating;
    /** 逻辑删除标记。 */ private Integer delFlag;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 更新时间。 */ private LocalDateTime updateTime;
}
