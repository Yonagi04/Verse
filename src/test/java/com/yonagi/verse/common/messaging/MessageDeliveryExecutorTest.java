package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.config.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MessageDeliveryExecutorTest {
    @Test void saturatedPoolNeverRunsTaskOnCallerThread() throws Exception {
        var properties = new MessagingProperties(); properties.setMaxConcurrency(1); properties.getAsync().setQueueCapacity(1);
        var pool = new MessageDeliveryExecutorConfiguration().smsDeliveryExecutor(properties); pool.initialize();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var rejectedRan = new AtomicBoolean();
        try {
            pool.execute(() -> {
                entered.countDown();
                try { assertTrue(finish.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS)); pool.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> rejectedRan.set(true)));
            assertFalse(rejectedRan.get()); assertEquals(1, pool.getThreadPoolExecutor().getQueue().size());
        } finally { finish.countDown(); pool.shutdown(); }
        assertFalse(rejectedRan.get());
    }
}
