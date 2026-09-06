package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.TokenUsageCostMapper;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageEventOperationsServiceImplTest {
    @Test
    void springInjectsTheOnlyRequiredArgsConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TokenUsageOutboxMapper.class, () -> mock(TokenUsageOutboxMapper.class));
            context.registerBean(UserTenantMapper.class, () -> mock(UserTenantMapper.class));
            context.registerBean(TokenUsageCostMapper.class, () -> mock(TokenUsageCostMapper.class));
            context.register(UsageEventOperationsServiceImpl.class);

            context.refresh();

            assertNotNull(context.getBean(UsageEventOperationsServiceImpl.class));
        }
    }

    @Test
    void manualReplayKeepsOriginalEventId() {
        TokenUsageOutboxMapper outboxMapper = mock(TokenUsageOutboxMapper.class);
        UserTenantMapper userTenantMapper = mock(UserTenantMapper.class);
        when(userTenantMapper.selectCount(any())).thenReturn(1L);
        when(outboxMapper.resetForReplay(eq(2L), eq("event-7"), any())).thenReturn(1);
        UsageEventOperationsServiceImpl service = new UsageEventOperationsServiceImpl(
                outboxMapper, userTenantMapper, null);

        assertTrue(service.replay(1L, 2L, "event-7"));

        verify(outboxMapper).resetForReplay(eq(2L), eq("event-7"), any());
    }
}
