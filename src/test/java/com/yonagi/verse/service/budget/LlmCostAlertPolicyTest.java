package com.yonagi.verse.service.budget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.dto.req.CostWarningPatch;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class LlmCostAlertPolicyTest {
    @Test void exactThresholdAndLimitDoNotRoundSmallAmounts() {
        var limit=new BigDecimal("10");
        assertNull(LlmCostAlertPolicy.event(true,limit,new BigDecimal("7.999999999999999999"),true,80));
        assertEquals("WARNING",LlmCostAlertPolicy.event(true,limit,new BigDecimal("8"),true,80));
        assertEquals("LIMIT",LlmCostAlertPolicy.event(true,limit,new BigDecimal("10"),true,80));
        assertEquals("LIMIT",LlmCostAlertPolicy.event(true,limit,new BigDecimal("11"),false,80));
        assertNull(LlmCostAlertPolicy.event(false,limit,new BigDecimal("11"),true,80));
        assertNull(LlmCostAlertPolicy.event(true,null,new BigDecimal("11"),true,80));
    }
    @Test void warningPatchRejectsCoercionNullUnknownAndOutOfRange() throws Exception {
        var json=new ObjectMapper();
        for(String bad: new String[]{"{\"thresholdPercent\":49}","{\"thresholdPercent\":100}","{\"thresholdPercent\":80.0}",
                "{\"thresholdPercent\":\"80\"}","{\"enabled\":null}","{\"channels\":[\"PUSH\"]}","{\"channels\":[null]}"})
            assertThrows(Exception.class,()->json.readValue(bad,CostWarningPatch.class));
        var patch=json.readValue("{\"thresholdPercent\":80,\"channels\":[\"EMAIL\",\"SMS\",\"EMAIL\"]}",CostWarningPatch.class);
        assertEquals(java.util.List.of("SMS","EMAIL"),patch.getChannels());
    }
    @Test void smsUsesRemainingAndPreservesTinyNonzero() {
        assertEquals("<0.01",LlmCostAlertTemplates.yuan(new BigDecimal("0.000000000000000001")));
        var e=new com.yonagi.verse.dao.entity.LlmCostAlertEventDO(); e.setEventType("WARNING"); e.setTenantName("租户");e.setServiceName("模型");
        e.setPeriodType("DAY");e.setRemainingCostFen(new BigDecimal("200"));e.setUsedCostFen(new BigDecimal("800"));
        assertEquals("2.00",LlmCostAlertTemplates.sms(e).get("remainingYuan"));
        assertEquals("每日",LlmCostAlertTemplates.sms(e).get("period"));
    }
}
