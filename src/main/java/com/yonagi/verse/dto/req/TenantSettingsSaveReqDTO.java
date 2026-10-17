package com.yonagi.verse.dto.req;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class TenantSettingsSaveReqDTO extends TenantSettingsUpdateReqDTO {
    /** 基础设置版本。 */
    @NotBlank @Pattern(regexp = "[0-9]{1,19}") private String settingsVersion;
    /** 可省略的成本补丁。 */ @Valid private CostLimitPatch costLimit;
    /** 可省略的预警补丁。 */ @Valid private CostWarningPatch costWarning;
    /** 本次完整目标的确认凭证。 */ @lombok.ToString.Exclude private String confirmationToken;
    @JsonSetter public void setCostLimit(CostLimitPatch value) {
        if (value == null) throw new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG);
        costLimit = value;
    }
    @JsonSetter public void setCostWarning(CostWarningPatch value) {
        if (value == null) throw new ClientException(CostBudgetErrorCodeEnum.WARNING_INVALID);
        costWarning = value;
    }
}
