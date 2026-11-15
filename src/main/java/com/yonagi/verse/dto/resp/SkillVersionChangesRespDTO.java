package com.yonagi.verse.dto.resp;

import java.util.List;

/** 包含未变文件的完整分类，前端默认只显示变化。 */
public record SkillVersionChangesRespDTO(
        /** 固定基准节点。 */
        String baseVersionId,
        /** 基准平台号。 */
        String baseVersion,
        /** 目标节点，待发布区为空。 */
        String targetVersionId,
        /** 文件分类。 */
        List<SkillFileChangeRespDTO> items) {}
