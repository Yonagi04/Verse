package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** 广场公开查询条件；未提供的默认值由查询用例明确处理。 */
@Getter
@Setter
public class SkillSearchReqDTO {
    /** 仅搜索展示名与平台作者。 */
    @Size(max = 80)
    private String keyword;
    /** 平台主分类。 */
    @Size(max = 32)
    private String category;
    /** 标签组内OR，最多五个。 */
    @Size(max = 5)
    private List<@Size(max = 32) String> tags;
    /** P1仅ZIP。 */
    @Size(max = 16)
    private String source;
    /** 精确平台作者条件。 */
    @Min(1)
    private Long publisherId;
    /** updated或relevance。 */
    @Size(max = 16)
    private String sort;
    /** 从一开始的页码。 */
    @Min(1)
    @Max(10000)
    private Integer page;
    /** 每页12或24。 */
    private Integer size;
}
