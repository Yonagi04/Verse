package com.yonagi.verse.dto.resp;

/** 两侧分别授权，null只代表该侧确实不存在该文件。 */
public record SkillFileComparisonRespDTO(
        /** 固定比较基准。 */
        String baseVersionId,
        /** 目标节点，待发布区为空。 */
        String targetVersionId,
        /** 文件变化类型。 */
        String changeType,
        /** 旧正文或明确能力状态。 */
        SkillFileContentRespDTO oldFile,
        /** 新正文或明确能力状态。 */
        SkillFileContentRespDTO newFile) {}
