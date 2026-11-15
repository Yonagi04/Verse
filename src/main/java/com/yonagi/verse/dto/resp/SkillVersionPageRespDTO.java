package com.yonagi.verse.dto.resp;

import java.util.List;

/** 固定头部序列的私人时间线分页。 */
public record SkillVersionPageRespDTO(
        /** 当前页。 */
        Integer page,
        /** 固定页大小20。 */
        Integer size,
        /** 固定头部以内节点数。 */
        Long total,
        /** 首次读取的头部序列。 */
        String headSequence,
        /** 节点按成功顺序倒序。 */
        List<SkillVersionRespDTO> items) {}
