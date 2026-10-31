package com.yonagi.verse.service.skillhub;

/** 已授权不可变字节信息，仅在服务内使用，不暴露内部Key。 */
record SkillDownloadResource(Long versionId, String filename, String archiveKey, String sha256, long bytes) {
    @Override
    public String toString() {
        return "SkillDownloadResource[versionId=" + versionId + "]";
    }
}
