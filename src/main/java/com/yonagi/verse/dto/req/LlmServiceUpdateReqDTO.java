package com.yonagi.verse.dto.req;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * LLM 服务更新请求。
 *
 * <p>采用「部分更新」语义：字段为 {@code null} 或空白字符串表示「不修改」，仅提交需要变更的字段即可。
 * 例如只改模型名称时，只需传 {@code name}，其余字段留空。</p>
 *
 * <p>注意：{@code apiKey} 在详情接口中返回的是脱敏值，前端<b>不要</b>将脱敏值回传；
 * 仅在需要修改 API Key 时填写新值，留空表示保持不变。</p>
 *
 * @author Yonagi
 * @version 1.1
 * @program Verse
 * @description
 * @date 2026/08/23 09:52
 */
@Data
public class LlmServiceUpdateReqDTO {

    /**
     * 模型注册名称（租户内唯一）。留空表示不修改；填写时不能超过 20 字。
     */
    @Length(max = 20, message = "模型注册名称不能超过20个字")
    private String name;

    /**
     * 供应商的 API 地址。留空表示不修改。
     */
    private String apiUrl;

    /**
     * 供应商的 API Key。留空表示不修改；填写时视为新 Key（加密存储）。
     */
    private String apiKey;

    /**
     * 供应商侧记录的模型名称。留空表示不修改。
     */
    private String modelName;
}
