package com.yonagi.verse.common.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryFenceRecoveryTest {
    @QueryCacheDependencies("t_test") static class Queries { }
    private final QueryCache cache = mock(QueryCache.class);
    private final QueryCatalogue catalogue = new QueryCatalogue();
    private final QueryCacheProperties properties = new QueryCacheProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private QueryFenceRecovery recovery() {
        catalogue.register(Queries.class);
        return new QueryFenceRecovery(cache, catalogue, properties, metrics);
    }

    @Test void failedCompletionRecordIsRetriedWithoutReleasingUnknownWriter() {
        when(cache.afterWrite("t_test", "known")).thenThrow(new IllegalStateException("redis unavailable")).thenReturn(true);
        when(cache.fenceStats("t_test")).thenReturn(new long[]{1, 60000, 0, 0});
        var recovery = recovery();
        recovery.completed("t_test", "known");
        recovery.unknown("t_test", "unknown");
        assertEquals(1, metrics.get("verse.query.cache.fence.pending").gauge().value());
        recovery.recover();
        verify(cache, times(2)).afterWrite("t_test", "known");
        verify(cache, never()).afterWrite("t_test", "unknown");
        assertEquals(0, metrics.get("verse.query.cache.fence.pending").gauge().value());
        assertEquals(60, metrics.get("verse.query.cache.fence.oldest.seconds").tag("table", "t_test").gauge().value());
    }

    @Test void pendingQueueAndPersistedScanAreBounded() {
        properties.setFencePendingLimit(1); properties.setFenceRetryBatch(1);
        when(cache.completedWriters("t_test", 1)).thenReturn(Set.of("persisted"));
        when(cache.cleanupCompleted("t_test", "persisted")).thenReturn(true);
        var recovery = recovery();
        recovery.completed("t_test", "first"); recovery.completed("t_test", "overflow");
        assertEquals(1, metrics.get("verse.query.cache.fence.pending").gauge().value());
        assertEquals(1, metrics.get("verse.query.cache.fence.cleanup").tags("table", "t_test", "outcome", "overflow").counter().count());
        recovery.recover();
        verify(cache, times(2)).afterWrite("t_test", "first");
        verify(cache, times(1)).afterWrite("t_test", "overflow");
        verify(cache).completedWriters("t_test", 1);
        verify(cache).cleanupCompleted("t_test", "persisted");
    }

    @Test void failedScanIsVisibleAndNeverReportsHealthyZero() {
        when(cache.completedWriters("t_test", 32)).thenThrow(new IllegalStateException("redis unavailable"));
        var recovery = recovery(); recovery.recover();
        assertTrue(Double.isNaN(metrics.get("verse.query.cache.fence.writers").tag("table", "t_test").gauge().value()));
        assertEquals(1, metrics.get("verse.query.cache.fence.cleanup").tags("table", "t_test", "outcome", "scan_failure").counter().count());
        verify(cache, never()).cleanupCompleted(anyString(), anyString());
    }
}
