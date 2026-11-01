package com.yonagi.verse.adapter.skillhub.validation.exception;

/** 校验拒绝只输出固定原因，不回显包内容、秘密或文件路径。 */
public class SkillPackageValidationException extends RuntimeException {
    private final String code;
    private final String relativePath;

    /**
     * @param code 固定机器原因
     * @param message 可安全展示的固定说明
     */
    public SkillPackageValidationException(String code, String message) {
        this(code, message, null);
    }

    /** 路径只能来自已校验的相对文件名，不允许携带检测到的秘密内容。 */
    public SkillPackageValidationException(String code, String message, String relativePath) {
        super(message);
        this.code = code;
        this.relativePath = relativePath;
    }

    /**
     * @return 固定机器原因
     */
    public String getCode() {
        return code;
    }

    public String getRelativePath() {
        return relativePath;
    }
}
