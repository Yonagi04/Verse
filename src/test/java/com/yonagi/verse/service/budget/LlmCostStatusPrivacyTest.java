package com.yonagi.verse.service.budget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.dto.resp.LlmCostStatusRespDTO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LlmCostStatusPrivacyTest {
    @Test void memberDtoCannotSerializeAdminFields() throws Exception {
        var response=new LlmCostStatusRespDTO.Summary("123","SUMMARY","UNKNOWN",null,"Asia/Shanghai","now",List.of(),"预算校验暂不可用");
        var json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertEquals("SUMMARY",json.get("visibility").asText());
        for(String field:List.of("costLimit","costWarning","pricingEligible","periods","usedCostFen","channels")) assertFalse(json.has(field));
    }
}
