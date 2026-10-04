package com.yonagi.verse.service.budget;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class BudgetPeriodResolverTest {
    @Test void crossMonthWeekUsesMondayAndShanghaiRegardlessOfJvmZone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            var resolver = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC));
            var periods = resolver.resolve(resolver.now());
            assertEquals(LocalDateTime.parse("2026-09-28T00:00:00"), periods.get(1).start());
            assertEquals("2026-10-05T00:00+08:00", resolver.iso(periods.get(1).end()));
            assertEquals("2026-10-01T00:00+08:00", resolver.iso(periods.get(2).end()));
        } finally { TimeZone.setDefault(previous); }
    }
    @Test void boundariesAndLateSettlementUseOriginalStart() {
        var resolver = new BudgetPeriodResolver();
        var before = resolver.resolve(Instant.parse("2026-10-31T15:59:59.999Z"));
        var after = resolver.resolve(Instant.parse("2026-10-31T16:00:00Z"));
        assertEquals(before.get(0).end(), after.get(0).start());
        assertEquals(before.get(2).end(), after.get(2).start());
        assertEquals(before.get(1), after.get(1));
        var monday = resolver.resolve(Instant.parse("2026-11-01T16:00:00Z"));
        assertEquals(before.get(1).end(), monday.get(1).start());
    }
}
