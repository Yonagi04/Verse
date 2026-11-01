package com.yonagi.verse.dto.resp;

import java.util.List;

/** 私人当前制品文件清单，只预览路径与摘要，不开放任意对象读取。 */
public record SkillDraftFilesRespDTO(
        /** 当前页，一基。 */ Integer page,
        /** 每页50或100项。 */ Integer size,
        /** 已检查文件总量。 */ Integer total,
        /** 路径稳定排序的当前页。 */ List<SkillDraftFileRespDTO> items,
        /** 响应绑定的当前制品。 */ String artifactId,
        /** 响应绑定的内容摘要。 */ String contentSha256) {
}
