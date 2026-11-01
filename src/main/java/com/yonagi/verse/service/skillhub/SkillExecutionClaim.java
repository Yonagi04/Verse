package com.yonagi.verse.service.skillhub;

/** 执行租约快照，包含内部对象Key和令牌，禁止日志展开。 */
record SkillExecutionClaim(Long jobId, Long draftId, Long userId, String token,
                            String sourceKey, String sourceSha256, long sourceBytes,
                            String sourceType, String phase, java.time.Instant deadline) {
    SkillExecutionClaim(Long jobId, Long draftId, Long userId, String token,
                         String sourceKey, String sourceSha256, long sourceBytes) {
        this(jobId, draftId, userId, token, sourceKey, sourceSha256, sourceBytes, "ZIP", "VALIDATE",
                java.time.Instant.now().plusSeconds(600));
    }
    @Override
    public String toString() {
        return "SkillExecutionClaim[jobId=" + jobId + "]";
    }
}
