package com.yonagi.verse.dto.skillhub;

/** 服务端获取的不可变来源，与作者自填来源和身份声明区分。 */
public record SkillSourceDTO(
        /** 来源快照格式版本。 */
        Integer schemaVersion,
        /** ZIP或GITHUB。 */
        String type,
        /** 规范公开仓库地址。 */
        String repositoryUrl,
        /** GitHub仓库稳定标识。 */
        String repositoryId,
        /** 用户当时请求的引用。 */
        String requestedRef,
        /** DEFAULT、BRANCH、TAG或COMMIT。 */
        String resolvedRefType,
        /** 固定完整提交SHA。 */
        String commitSha,
        /** 所选Skill相对于仓库的目录，根目录为空串。 */
        String directory,
        /** 实际源包成功获取时间，V1历史未知。 */
        java.time.OffsetDateTime fetchedAt) {
    /** 兼容真实V1快照，未知获取时间保持为空。 */
    public SkillSourceDTO(
            Integer schemaVersion,
            String type,
            String repositoryUrl,
            String repositoryId,
            String requestedRef,
            String resolvedRefType,
            String commitSha,
            String directory) {
        this(
                schemaVersion,
                type,
                repositoryUrl,
                repositoryId,
                requestedRef,
                resolvedRefType,
                commitSha,
                directory,
                null);
    }

    /** @return 旧ZIP不伪造已验证的远程来源 */
    public static SkillSourceDTO zip() {
        return new SkillSourceDTO(1, "ZIP", null, null, null, null, null, null);
    }
}
