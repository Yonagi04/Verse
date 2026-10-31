package com.yonagi.verse.service.skillhub;

/** 私有上传代次，只在Service内传递，不能进入HTTP或日志。 */
record SkillUploadClaim(Long jobId, Long draftId, Long userId, String token, String sourceKey) {
    @Override
    public String toString() {
        return "SkillUploadClaim[jobId=" + jobId + "]";
    }
}
