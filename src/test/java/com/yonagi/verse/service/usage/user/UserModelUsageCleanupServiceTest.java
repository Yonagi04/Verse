package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.dao.mapper.UserModelUsageCleanupMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelUsageCleanupServiceTest {
    @Test void cleanupContinuesUntilBothBoundedContributionBatchesAreFinished() {
        var mapper=mock(UserModelUsageCleanupMapper.class); var service=new UserModelUsageCleanupService(mapper);
        when(mapper.work(1L)).thenReturn(500);
        assertTrue(service.cleanBatch(1L));
        when(mapper.work(1L)).thenReturn(0); when(mapper.daily(1L)).thenReturn(500);
        assertTrue(service.cleanBatch(1L));
        when(mapper.daily(1L)).thenReturn(0);
        assertFalse(service.cleanBatch(1L));
    }
}
