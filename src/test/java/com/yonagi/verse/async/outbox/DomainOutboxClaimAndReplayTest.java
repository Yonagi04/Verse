package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DomainOutboxClaimAndReplayTest {
    @Test void claimSqlUsesSkipLockedForMultiInstanceExclusion() throws Exception {
        String sql=String.join(" ",DomainEventOutboxMapper.class
                .getMethod("lockClaimable",LocalDateTime.class,int.class)
                .getAnnotation(Select.class).value());
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(sql.contains("claim_expires_at <="));
    }
    @Test void expiredLeaseCanBeTakenOver(){
        DomainEventOutboxMapper mapper=mock(DomainEventOutboxMapper.class); DomainEventOutboxDO row=new DomainEventOutboxDO(); row.setId(9L);
        row.setClaimOwner("dead");row.setClaimExpiresAt(LocalDateTime.now().minusMinutes(1));when(mapper.lockClaimable(any(),anyInt())).thenReturn(List.of(row));
        when(mapper.claim(anyList(),anyString(),any(),any())).thenReturn(1);DomainOutboxClaimService service=new DomainOutboxClaimService(mapper,new DomainOutboxProperties());
        assertEquals("new",service.claim("new").getFirst().getClaimOwner());verify(mapper).claim(eq(List.of(9L)),eq("new"),any(),any());
    }
    @Test void replayRejectsExistingFactAndRestoresMissingFact(){
        DomainEventOutboxMapper outbox=mock(DomainEventOutboxMapper.class);TenantActivityLogMapper facts=mock(TenantActivityLogMapper.class);
        TenantActivityReplayService service=new TenantActivityReplayService(outbox,facts);when(facts.countByEventId("e")).thenReturn(1L);
        assertFalse(service.replay(20L,"e"));verify(outbox,never()).resetForReplay(anyLong(),anyString(),any());
        when(facts.countByEventId("e")).thenReturn(0L);when(outbox.resetForReplay(eq(20L),eq("e"),any())).thenReturn(1);
        assertTrue(service.replay(20L,"e"));
    }
}
