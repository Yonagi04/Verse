package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import static org.junit.jupiter.api.Assertions.*;

/** 两个独立客户端使用真实 Redis 验证 nonce、终态同步边界和启动代次。 */
@EnabledIfEnvironmentVariable(named = "VERSE_BUDGET_TEST_REDIS", matches = ".+")
class BudgetExecutionRegistryIntegrationTest {
    private RedissonClient firstClient;
    private RedissonClient secondClient;
    private BudgetExecutionRegistry first;
    private BudgetExecutionRegistry second;
    @BeforeEach void setup() {
        Config config = new Config();
        var server = config.useSingleServer().setAddress(System.getenv("VERSE_BUDGET_TEST_REDIS"));
        String password = System.getenv("VERSE_BUDGET_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) server.setPassword(password);
        firstClient = Redisson.create(config); secondClient = Redisson.create(config);
        first = new BudgetExecutionRegistry(firstClient); second = new BudgetExecutionRegistry(secondClient);
        first.listen(); second.listen();
    }
    @AfterEach void close() {
        if (first != null) first.close(); if (second != null) second.close();
        if (firstClient != null) firstClient.shutdown(); if (secondClient != null) secondClient.shutdown();
    }
    @Test void remoteProofExpiresAtTerminalAndNeverReusesOldGeneration() {
        first.register("proof-request"); first.sent("proof-request");
        var invocation = new CostBudgetInvocationDO(); invocation.setRequestId("proof-request");
        invocation.setOwnerId(first.owner()); invocation.setOwnerGeneration(first.generation());
        assertTrue(second.confirm(invocation));
        first.finalizing("proof-request"); assertFalse(second.confirm(invocation));
        first.register("other"); invocation.setRequestId("other"); invocation.setOwnerGeneration("old-generation");
        assertFalse(second.confirm(invocation));
        invocation.setOwnerId("missing-owner"); assertFalse(second.confirm(invocation));
    }
    @Test void unconsumedPermitCanBeAbandonedButCannotLaterSend() {
        first.register("unsubscribed"); assertTrue(first.noUpstream("unsubscribed"));
        assertThrows(IllegalStateException.class, () -> first.sent("unsubscribed"));
        first.register("streaming"); first.sent("streaming"); assertFalse(first.noUpstream("streaming"));
    }
}
