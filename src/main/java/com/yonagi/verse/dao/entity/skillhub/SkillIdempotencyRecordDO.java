package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 关键命令的幂等回执，不替代长期业务唯一约束。 */
@Getter
@Setter
@TableName("t_skill_command")
public class SkillIdempotencyRecordDO {
    /** 幂等命令回执标识。 */
    @TableId(type = IdType.INPUT)
    private Long commandId;
    /** 账号主体作用域。 */
    private String subjectScope;
    /** 用例命令类型。 */
    private String commandType;
    /** 客户端UUID幂等键。 */
    private String idempotencyKey;
    /** 规范化请求摘要。 */
    private String requestHash;
    /** 原操作结果资源。 */
    private Long resourceId;
    /** 用于原样恢复结果的有界回执。 */
    private String responseJson;
    /** 短期回执清理期限。 */
    private LocalDateTime expiresAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
