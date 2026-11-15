package com.yonagi.verse.dto.resp;

/** 两份完整清单按相对路径和文件SHA比较的结果。 */
public record SkillFileChangeRespDTO(
        /** 规范相对路径。 */
        String relativePath,
        /** ADDED、MODIFIED、DELETED或UNCHANGED。 */
        String changeType,
        /** 旧文件，新增时为空。 */
        SkillFileRespDTO oldFile,
        /** 新文件，删除时为空。 */
        SkillFileRespDTO newFile) {}
