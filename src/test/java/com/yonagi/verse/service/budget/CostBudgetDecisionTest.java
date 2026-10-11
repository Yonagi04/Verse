package com.yonagi.verse.service.budget;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CostBudgetDecisionTest {
    @Test void latestRealEndWinsAcrossScopesAndUnknownHasPriority() {
        var key=new Hit("API_KEY","MONTH","2026-10-01T00:00:00+08:00");
        var llm=new Hit("LLM","WEEK","2026-10-05T00:00:00+08:00");
        var decision=new CostBudgetDecision(List.of(key,llm),false);
        assertEquals(llm,decision.limits().getFirst());
        var error=assertThrows(CostLimitExceededException.class,decision::enforce);
        assertTrue(error.getMessage().contains("此模型 本周"));
        assertThrows(CostBudgetUnavailableException.class,()->new CostBudgetDecision(decision.limits(),true).enforce());
    }
    @Test void equalEndKeepsScopeThenPeriodStable() {
        String end="2026-10-12T00:00:00+08:00";
        var decision=new CostBudgetDecision(List.of(new Hit("LLM","MONTH",end),new Hit("API_KEY","DAY",end),new Hit("API_KEY","WEEK",end)),false);
        assertEquals("API_KEY",decision.limits().getFirst().scope()); assertEquals("WEEK",decision.limits().getFirst().period());
    }
}
