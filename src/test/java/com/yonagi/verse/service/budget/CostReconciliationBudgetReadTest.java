package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostReconciliationBudgetReadTest {
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final CostReconciliationBudgetRead service = new CostReconciliationBudgetRead(
            mock(CostReconciliationTracking.class), settlements, mock(CostReconciliationCaseMapper.class),
            new BudgetPeriodResolver());

    @Test
    void identityConflictAnd1001ConfirmedFactsFailClosed() {
        var instant = Instant.parse("2026-11-14T00:00:00Z");
        when(settlements.hasInvalidPendingConfirmed(eq(7L), any(), any())).thenReturn(true);
        assertFalse(service.staged(7L, instant).available());
        when(settlements.hasInvalidPendingConfirmed(eq(7L), any(), any())).thenReturn(false);
        when(settlements.pendingConfirmed(eq(7L), any(), any()))
                .thenReturn(Collections.nCopies(1001, new CostBudgetSettlementDO()));
        assertFalse(service.staged(7L, instant).available());
        var fact = new CostBudgetSettlementDO(); fact.setEventId("confirmed-original");
        when(settlements.pendingConfirmed(eq(7L), any(), any())).thenReturn(List.of(fact));
        var result = service.staged(7L, instant);
        assertTrue(result.available());
        assertEquals(List.of(fact), result.rows());
    }
}
