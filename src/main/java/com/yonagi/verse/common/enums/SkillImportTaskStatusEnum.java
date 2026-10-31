package com.yonagi.verse.common.enums;

/** SkillImportTaskStatus的稳定持久化状态。 */
public enum SkillImportTaskStatusEnum {
    /** 等待源ZIP。 */
    WAITING_UPLOAD,
    /** 已领取上传代次。 */
    UPLOADING,
    /** 已绑定输入等待执行。 */
    QUEUED,
    /** 持有效租约执行。 */
    RUNNING,
    /** 暂时故障等待重试。 */
    RETRY_WAIT,
    /** 检查成功。 */
    SUCCEEDED,
    /** 检查失败。 */
    FAILED,
    /** 用户取消。 */
    CANCELLED;
}
