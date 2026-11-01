package com.yonagi.verse.service.skillhub;

/** 经过语法验证的公开来源，不保存原始凭据或任意主机。 */
public record SkillGithubSourceRequest(
        /** 仓库所有者。 */ String owner,
        /** 无.git后缀的仓库名。 */ String repository,
        /** DEFAULT、AUTO、BRANCH、TAG或COMMIT。 */ String refType,
        /** 原始引用，默认分支为空。 */ String ref,
        /** 显式扫描目录。 */ String directory,
        /** tree链接的完整待消歧尾部，根链接为空。 */ String treeTail) {
    /** @return 仅供显示和受限API构建的规范仓库地址 */
    public String repositoryUrl() {
        return "https://github.com/" + owner + "/" + repository;
    }
}
