package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageEventOperationsServiceImplTest {
    @Test
    void manualReplayKeepsOriginalEventId() {
        TokenUsageOutboxMapper outboxMapper = mock(TokenUsageOutboxMapper.class);
        UserTenantMapper userTenantMapper = mock(UserTenantMapper.class);
        when(userTenantMapper.selectCount(any())).thenReturn(1L);
        when(outboxMapper.resetForReplay(eq(2L), eq("event-7"), any())).thenReturn(1);
        UsageEventOperationsServiceImpl service = new UsageEventOperationsServiceImpl(outboxMapper, userTenantMapper);

        assertTrue(service.replay(1L, 2L, "event-7"));

        verify(outboxMapper).resetForReplay(eq(2L), eq("event-7"), any());
    }
}
