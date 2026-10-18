package com.yonagi.verse.service.user.costalert;

import java.math.BigDecimal;

/** 原始精度已知费用达到绝对阈值即可触发；未知余量不影响已知下界。 */
public final class UserCostAlertThresholdPolicy {
    private UserCostAlertThresholdPolicy() { }
    public static boolean reached(boolean enabled,BigDecimal threshold,BigDecimal known) {
        return enabled && threshold!=null && threshold.signum()>0 && known.compareTo(threshold)>=0;
    }
}
