package com.yonagi.verse.dto.resp;

/** 当前公开版本的详情；不可用占位不返回正文和作者声明。 */
public record SkillDetailRespDTO(
        /** 已批准的公开卡片。 */ SkillCardRespDTO skill,
        /** AVAILABLE或UNAVAILABLE，不泄露私有草稿状态。 */ String availability,
        /** 同一版本的SKILL.md。 */ String skillMarkdown,
        /** 同一版本的许可证声明。 */ String license,
        /** 作者自填原作者，与平台作者分开展示。 */ String originalAuthor,
        /** 作者提供的HTTP/HTTPS来源。 */ String sourceUrl,
        /** 作者声明的客户端兼容性。 */ String compatibility,
        /** 作者声明的依赖及环境变量名称。 */ String dependencies,
        /** 固定ZIP字节数。 */ Long archiveBytes,
        /** 固定ZIP摘要。 */ String sha256) {
    @Override
    public String toString() {
        return "SkillDetailRespDTO[skillId=" + skill.skillId() + ",availability=" + availability + "]";
    }
}
