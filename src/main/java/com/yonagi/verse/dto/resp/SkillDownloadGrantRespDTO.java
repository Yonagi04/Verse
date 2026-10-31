package com.yonagi.verse.dto.resp;

import java.time.OffsetDateTime;

/** 短效Verse受控下载地址，不是对象存储地址。 */
public record SkillDownloadGrantRespDTO(
        /** 站内60秒下载路径。 */ String downloadUrl,
        /** 原授权过期时间。 */ OffsetDateTime expiresAt,
        /** 固定版本包摘要。 */ String sha256,
        /** 固定版本ZIP大小。 */ Long archiveBytes) {
    @Override
    public String toString() {
        return "SkillDownloadGrantRespDTO[expiresAt=" + expiresAt + "]";
    }
}
