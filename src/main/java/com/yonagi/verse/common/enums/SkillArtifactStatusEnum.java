package com.yonagi.verse.common.enums;

/** SkillArtifactStatus的稳定持久化状态。 */
public enum SkillArtifactStatusEnum {
    /** 允许绑定与授权的私有快照。 */
    READY,
    /** 已阻止新增引用等待删除。 */
    DELETE_PENDING,
    /** 对象已确认删除。 */
    DELETED;
}
