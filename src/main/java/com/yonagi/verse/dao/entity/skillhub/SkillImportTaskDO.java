package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** ZIP导入与检查任务的执行状态，不代表公开发布状态。 */
@Getter
@Setter
@TableName("t_skill_job")
public class SkillImportTaskDO {
    /** 导入任务标识。 */
    @TableId(type = IdType.INPUT)
    private Long jobId;
    /** 关联本人草稿。 */
    private Long draftId;
    /** 任务所属账号。 */
    private Long ownerUserId;
    /** ZIP或GITHUB，旧任务保持ZIP。 */
    private String sourceType;
    /** RESOLVE、FETCH、SELECT或VALIDATE。 */
    private String phase;
    /** GitHub阶段首次执行前的排队截止，UTC。 */
    private LocalDateTime queueDeadline;
    /** 来源确认或目录选择的等待截止，UTC。 */
    private LocalDateTime waitUntil;
    /** 私有且不可覆盖的源包Key。 */
    private String sourceKey;
    /** 已绑定源ZIP字节摘要。 */
    private String sourceSha256;
    /** 源ZIP实际字节数。 */
    private Long sourceBytes;
    /** 上传领取代次，仅服务端使用。 */
    private String uploadToken;
    /** 上传领取截止时间。 */
    private LocalDateTime uploadUntil;
    /** 上传完成后固定的处理截止时间，UTC；等待上传及历史任务可为空。 */
    private LocalDateTime processingDeadline;
    /** Worker执行代次。 */
    private String executionToken;
    /** 执行租约截止时间。 */
    private LocalDateTime leaseUntil;
    /** 上传、阶段执行、来源确认、目录选择或终态。 */
    private String status;
    /** 已领取执行次数。 */
    private Integer attemptCount;
    /** 下次允许执行时间。 */
    private LocalDateTime nextRetryAt;
    /** 可机器识别的检查失败原因。 */
    private String errorCode;
    /** 不含秘密的失败说明。 */
    private String publicReason;
    /** 成功产出的私有制品。 */
    private Long artifactId;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近变更时间，UTC。 */
    private LocalDateTime updateTime;
}
