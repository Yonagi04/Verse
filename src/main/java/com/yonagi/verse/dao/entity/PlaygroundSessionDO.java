package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** PlayGround 会话，仅由所属租户和创建者访问。 */
@Data
@TableName("t_playground_session")
public class PlaygroundSessionDO {
    /** 自增主键。 */
    private Long id;
    /** 会话业务 ID。 */
    private Long sessionId;
    /** 租户业务 ID。 */
    private Long tenantId;
    /** 创建者业务 ID。 */
    private Long ownerUserId;
    /** 固定的模型服务业务 ID。 */
    private Long serviceId;
    /** 创建或换模型时的名称快照。 */
    private String modelName;
    /** 首条输入生成的标题。 */
    private String title;
    /** 已受理轮次数。 */
    private Integer turnCount;
    /** 是否有生成中的轮次。 */
    private Integer generating;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最近活动时间。 */
    private LocalDateTime updateTime;
    /** 逻辑删除标记。 */
    private Integer delFlag;
}
