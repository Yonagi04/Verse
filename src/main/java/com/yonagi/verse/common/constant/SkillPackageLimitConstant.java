package com.yonagi.verse.common.constant;

/** P1固定资源上限，前端通过options读取，不复制为另一套可调配置。 */
public final class SkillPackageLimitConstant {
    /** 从上传完成入队起算，重试和续租不得延长。 */
    public static final int PROCESSING_WINDOW_MINUTES = 10;
    public static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_UNPACKED_BYTES = 50L * 1024 * 1024;
    public static final int MAX_FILE_COUNT = 500;
    public static final int MAX_DIRECTORY_DEPTH = 10;
    public static final int MAX_SKILL_MARKDOWN_BYTES = 1024 * 1024;
    public static final int MAX_FRONTMATTER_BYTES = 64 * 1024;
    public static final int MAX_FORM_BYTES = 32 * 1024;

    private SkillPackageLimitConstant() {
    }
}
