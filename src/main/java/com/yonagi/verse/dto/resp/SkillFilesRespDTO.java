package com.yonagi.verse.dto.resp;

import java.util.List;

/** 有界版本文件清单，目录由规范化相对路径推导。 */
public record SkillFilesRespDTO(
        /** 清单所属固定版本。 */ String versionId,
        /** 规范化归档的包根名称。 */ String rootName,
        /** 单个文件文本预览字节上限。 */ Integer maxPreviewBytes,
        /** 已校验文件，最多五百项。 */ List<SkillFileRespDTO> items) {
}
