package com.yonagi.verse.adapter.skillhub.validation;

/** 取消、预算耗尽或执行代次失效属于执行失败，不能转为包内容拒绝。 */
final class SkillValidationCancelledException extends RuntimeException {
    SkillValidationCancelledException() {
        super("Skill校验已取消或超过执行预算");
    }
}
