package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * API Key 分页列表响应
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class ApiKeyPageRespDTO {

    /**
     * 当前页数据
     */
    private List<ApiKeyListRespDTO> records;

    /**
     * 总条数
     */
    private Long total;

    /**
     * 总页数
     */
    private Long totalPages;

    /**
     * 当前页码
     */
    private Integer page;

    /**
     * 每页条数
     */
    private Integer pageSize;
}
