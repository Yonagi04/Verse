package com.yonagi.verse.service.user.costalert;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserCostAlertThresholdPolicyTest {
    @Test void thresholdUsesFullPrecisionAndAcceptsCrossingEqualityWithoutRounding() {
        assertFalse(UserCostAlertThresholdPolicy.reached(true,new BigDecimal("1"),new BigDecimal("0.999999999999999999")));
        assertTrue(UserCostAlertThresholdPolicy.reached(true,new BigDecimal("1000"),new BigDecimal("1000")));
        assertTrue(UserCostAlertThresholdPolicy.reached(true,new BigDecimal("1000"),new BigDecimal("1200")));
        assertFalse(UserCostAlertThresholdPolicy.reached(false,BigDecimal.ONE,BigDecimal.TEN));
        assertFalse(UserCostAlertThresholdPolicy.reached(true,null,BigDecimal.TEN));
        assertFalse(UserCostAlertThresholdPolicy.reached(true,BigDecimal.ZERO,BigDecimal.TEN));
    }
}
