package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageOutboxClaimServiceTest {
    @Test
    void expiredClaimCanBeClaimedByAnotherInstance() {
        TokenUsageOutboxMapper mapper = mock(TokenUsageOutboxMapper.class);
        UsageOutboxProperties properties = new UsageOutboxProperties();
        TokenUsageOutboxDO expired = new TokenUsageOutboxDO();
        expired.setId(9L);
        expired.setClaimOwner("stopped-instance");
        expired.setClaimExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.lockClaimable(any(), anyInt())).thenReturn(List.of(expired));
        when(mapper.claim(anyList(), anyString(), any(), any())).thenReturn(1);
        UsageOutboxClaimService service = new UsageOutboxClaimService(mapper, properties);

        List<TokenUsageOutboxDO> claimed = service.claim("new-instance");

        assertEquals("new-instance", claimed.getFirst().getClaimOwner());
        verify(mapper).claim(eq(List.of(9L)), eq("new-instance"), any(), any());
    }
}
