package com.yonagi.verse.dto.resp;

import java.util.List;

public record TenantSettingsSaveRespDTO(
        /** 保存结果。 */ String status,
        /** 是否等待确认。 */ boolean requiresConfirmation,
        /** 风险文案。 */ String warningMessage,
        /** 内容绑定凭证。 */ String confirmationToken,
        /** 凭证到期。 */ String confirmationExpiresAt,
        /** 最新设置版本。 */ String expectedSettingsVersion,
        /** 最新成本版本。 */ String expectedCostConfigVersion,
        /** 将立即命中的周期。 */ List<ApiKeyCostStatusRespDTO.Hit> immediateLimits,
        /** 同事务正式保存回执。 */ Snapshot savedSnapshot,
        /** 并发变更的正式对照。 */ Snapshot currentSnapshot) {
    public record Snapshot(
            /** 基础设置。 */ TenantSettingsRespDTO settings,
            /** 成本配置。 */ CostLimitConfig costLimit,
            /** 预警配置。 */ LlmCostStatusRespDTO.Warning costWarning) { }
    public static TenantSettingsSaveRespDTO saved(Snapshot snapshot) {
        return new TenantSettingsSaveRespDTO("SAVED", false, null, null, null, null, null, List.of(), snapshot, null);
    }
}
