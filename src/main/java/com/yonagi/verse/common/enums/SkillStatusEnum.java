package com.yonagi.verse.common.enums;

/** SkillStatus的稳定持久化状态。 */
public enum SkillStatusEnum {
    /** 尚未公开。 */
    DRAFT,
    /** 当前可公开分发。 */
    ACTIVE,
    /** 作者主动下架。 */
    OWNER_UNPUBLISHED,
    /** 平台停止分发。 */
    PLATFORM_DISABLED;
}
