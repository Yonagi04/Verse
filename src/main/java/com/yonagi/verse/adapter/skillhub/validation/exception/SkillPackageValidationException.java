package com.yonagi.verse.adapter.skillhub.validation.exception;

/** 校验拒绝只输出固定原因，不回显包内容、秘密或文件路径。 */
public class SkillPackageValidationException extends RuntimeException {
    private final String code;

    /**
     * @param code 固定机器原因
     * @param message 可安全展示的固定说明
     */
    public SkillPackageValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return 固定机器原因
     */
    public String getCode() {
        return code;
    }
}
