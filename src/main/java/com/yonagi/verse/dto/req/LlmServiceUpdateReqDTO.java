package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.List;

/**
 * LLM 服务更新请求。
 *
 * <p>采用「部分更新」语义：字段为 {@code null} 或空白字符串表示「不修改」，仅提交需要变更的字段即可。
 * 例如只改模型名称时，只需传 {@code name}，其余字段留空。</p>
 *
 * <p>限流与降级字段（{@code rpm}/{@code tpm}/{@code fallbackServiceId}）额外约定：
 * {@code 0} 表示「清除」对应配置（即不限/无降级），用于与「不修改」（{@code null}）区分。</p>
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

    /**
     * 模型介绍。{@code null}=不修改；空字符串=清空；非空字符串=更新，最多 100 个字符。
     */
    @Length(max = 100, message = "模型介绍不能超过100个字符")
    private String description;

    /**
     * 模型级 RPM 上限。{@code null}=不修改；{@code 0}=清除限制（不限）；正数=设置限制值。
     */
    @Min(value = 0, message = "RPM 不能为负数")
    private Integer rpm;

    /**
     * 模型级 TPM 上限。{@code null}=不修改；{@code 0}=清除限制（不限）；正数=设置限制值。
     */
    @Min(value = 0, message = "TPM 不能为负数")
    private Integer tpm;

    /**
     * 备用模型 serviceId。{@code null}=不修改；{@code 0}=清除降级配置；正数=设置备用模型。
     */
    @Min(value = 0, message = "备用模型 ID 不能为负数")
    private Long fallbackServiceId;

    /** {@code null} 表示不修改；空列表表示清空全部标签。 */
    private List<String> tagCodes;

    /** {@code null} 表示不修改；零表示清除；正数表示设置上下文窗口。 */
    @Min(value = 0, message = "上下文窗口不能为负数")
    private Long contextWindow;

    /** {@code null} 表示不修改；零表示清除；正数表示设置最大输出 Token 数。 */
    @Min(value = 0, message = "最大输出 Token 数不能为负数")
    private Long maxOutputTokens;

    /** {@code null} 表示不修改；禁用时关闭计费；启用时创建完整的新定价版本。 */
    private PricingConfigReqDTO pricing;
}
