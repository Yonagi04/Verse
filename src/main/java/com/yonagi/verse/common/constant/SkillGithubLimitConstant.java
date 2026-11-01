package com.yonagi.verse.common.constant;

/** GitHub仓库与所选Skill使用不同预算，不能放宽既有上传校验。 */
public final class SkillGithubLimitConstant {
    public static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_UNPACKED_BYTES = 250L * 1024 * 1024;
    public static final int MAX_TREE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ENTRIES = 10000;
    public static final int MAX_CANDIDATES = 100;
    public static final int MAX_ISSUES = 20;
    public static final int QUEUE_MINUTES = 10;
    public static final int WAIT_DAYS = 7;
    public static final String SOURCE_TYPE = "GITHUB";
    public static final String RESOLVE = "RESOLVE";
    public static final String FETCH = "FETCH";
    public static final String VALIDATE = "VALIDATE";

    private SkillGithubLimitConstant() {
    }
}
