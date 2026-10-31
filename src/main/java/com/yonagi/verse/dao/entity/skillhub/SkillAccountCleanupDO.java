package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 注销后的本域处置进度，区分可执行任务与等待保留政策。 */
@Getter
@Setter
@TableName("t_skill_account_cleanup")
public class SkillAccountCleanupDO {
    /** 已注销资源所有者。 */
    @TableId(type = IdType.INPUT)
    private Long userId;
    /** RUNNABLE、WAITING_RETENTION_POLICY或COMPLETED。 */
    private String state;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
