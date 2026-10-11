package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.RTopic;
import org.redisson.misc.CompletableFutureWrapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BudgetExecutionRegistryTest {
    @Test void remoteProofsShareOneDeadlineAndLocalTerminalInvalidatesImmediately() {
        var redis=mock(RedissonClient.class); var topic=mock(RTopic.class);
        when(redis.getTopic(anyString())).thenReturn(topic);
        when(topic.publishAsync(any())).thenReturn(new CompletableFutureWrapper<>(CompletableFuture.completedFuture(0L)));
        var registry=new BudgetExecutionRegistry(redis);
        var rows=IntStream.range(0,40).mapToObj(i -> invocation("remote-"+i,"missing-owner","generation")).toList();
        assertTimeout(Duration.ofSeconds(2),() -> assertTrue(registry.confirmed(rows).isEmpty()));
        registry.register("local"); var local=invocation("local",registry.owner(),registry.generation());
        assertEquals(java.util.Set.of("local"),registry.confirmed(List.of(local)));
        registry.finalizing("local"); assertFalse(registry.confirm(local));
        registry.register("next"); assertFalse(registry.confirm(invocation("next",registry.owner(),"old-generation")));
    }
    private CostBudgetInvocationDO invocation(String id,String owner,String generation) {
        var row=new CostBudgetInvocationDO(); row.setRequestId(id); row.setOwnerId(owner); row.setOwnerGeneration(generation); return row;
    }
}
