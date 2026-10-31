package com.yonagi.verse.service.skillhub;

/** 已完成最终授权和摘要校验的受限响应体。 */
public record SkillDownloadPayload(String filename, byte[] bytes, String sha256) {
    @Override
    public String toString() {
        return "SkillDownloadPayload[bytes=" + bytes.length + "]";
    }
}
