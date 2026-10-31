package com.yonagi.verse.common.enums;

/** SkillDraftStatus的稳定持久化状态。 */
public enum SkillDraftStatusEnum {
    /** 尚未就绪的本人草稿。 */
    EDITING,
    /** 绑定已通过检查的快照。 */
    READY,
    /** 已消费为不可变提交。 */
    SUBMITTED,
    /** 本人放弃。 */
    CANCELLED,
    /** 长期未使用而过期。 */
    EXPIRED;
}
