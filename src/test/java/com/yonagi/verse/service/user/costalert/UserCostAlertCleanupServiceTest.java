package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.dao.mapper.UserCostAlertCleanupMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertCleanupServiceTest {
    @Test void incompleteChildBatchesRetainEventAndConfigurationForNextCleanup() {
        var mapper=mock(UserCostAlertCleanupMapper.class); var service=new UserCostAlertCleanupService(mapper);
        when(mapper.deletePersonalNotifications(1L)).thenReturn(500);
        assertTrue(service.cleanBatch(1L)); verify(mapper,never()).events(anyLong()); verify(mapper,never()).config(anyLong());
        when(mapper.deletePersonalNotifications(1L)).thenReturn(0); when(mapper.deletePersonalRecipients(1L)).thenReturn(500);
        assertTrue(service.cleanBatch(1L)); verify(mapper,never()).events(anyLong());
        when(mapper.deletePersonalRecipients(1L)).thenReturn(0); when(mapper.deliveries(1L)).thenReturn(500);
        assertTrue(service.cleanBatch(1L)); verify(mapper,never()).events(anyLong());
    }
    @Test void configurationIsRemovedOnlyAfterAllEventBatchesComplete() {
        var mapper=mock(UserCostAlertCleanupMapper.class); var service=new UserCostAlertCleanupService(mapper);
        when(mapper.events(1L)).thenReturn(500,0);
        assertTrue(service.cleanBatch(1L)); verify(mapper,never()).config(anyLong());
        assertFalse(service.cleanBatch(1L)); verify(mapper).config(1L);
    }
}
