package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 既有账号的内部发布关联与冻结状态，不提供独立身份设置。 */
@Getter
@Setter
@TableName("t_skill_publisher")
public class SkillPublisherDO {
    /** 公开发布者标识。 */
    @TableId(type = IdType.INPUT)
    private Long publisherId;
    /** 所属个人账号。 */
    private Long userId;
    /** 内部稳定定位标识，自动生成；已有历史值保留。 */
    private String handle;
    /** 首次关联的账号显示名兼容列；展示实时读取用户资料。 */
    private String publicName;
    /** 发布资格：ACTIVE或CLOSED。 */
    private String status;
    /** 并发修订号。 */
    private Long revision;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
