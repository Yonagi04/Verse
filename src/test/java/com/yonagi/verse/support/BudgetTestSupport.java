package com.yonagi.verse.support;
import com.yonagi.verse.service.budget.*;
import com.yonagi.verse.dao.entity.TenantCostBudgetDO;
import java.util.List;
import static org.mockito.Mockito.*;
/** 一二期遗留 fixture 明确使用关闭的租户作用域，不扩张其基础设施场景。 */
public final class BudgetTestSupport {
    private BudgetTestSupport() { }
    public static TenantCostBudgetLedger disabledTenantLedger() {
        var ledger = mock(TenantCostBudgetLedger.class);
        when(ledger.lock(any())).thenAnswer(call -> { var row = new TenantCostBudgetDO(); row.setTenantId(call.getArgument(0)); row.setCostLimitEnabled(false); return row; });
        when(ledger.decision(any())).thenReturn(new CostBudgetDecision(List.of(),false));
        return ledger;
    }
}
