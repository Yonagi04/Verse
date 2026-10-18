package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.convention.exception.*;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.budget.CostBudgetService;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelUsageAvailabilityTest {
    @BeforeEach void tables() { UserUsageTestFixtures.tables(); }
    @Test void selfAccessRejectsMissingDisabledDeletedAccountsWithoutRequiringTenant() {
        var users=mock(UserMapper.class); var flags=new UserModelUsageProperties(); var outbox=new UsageOutboxProperties(); var access=new UserModelUsageAccessPolicy(users,flags,outbox);
        assertTrue(access.collecting()); outbox.setEnabled(false); assertFalse(access.collecting());
        assertThrows(ClientException.class,()->access.read(null)); assertThrows(ClientException.class,()->access.lock(null));
        assertThrows(ClientException.class,()->access.read(1L)); assertThrows(ClientException.class,()->access.lock(1L));
        var user=UserUsageTestFixtures.user(); when(users.selectOne(any())).thenReturn(user); when(users.selectActiveUserForUpdate(1L)).thenReturn(user);
        assertSame(user,access.read(1L)); assertSame(user,access.lock(1L));
        user.setStatus(0); assertThrows(ClientException.class,()->access.read(1L)); user.setStatus(1); user.setDelFlag(1); assertThrows(ClientException.class,()->access.lock(1L));
    }
    @Test void collectionWithoutPricingKeepsCanonicalIdentityAndPropagatesDurableHandoffFailure() {
        var properties=new UserModelUsageProperties(); var enabled=new UsageOutboxProperties(); enabled.setEnabled(false); var outbox=mock(UsageOutboxStager.class); var budgets=mock(CostBudgetService.class);
        var publisher=new UserModelUsageFactPublisher(properties,enabled,outbox,budgets,UserUsageTestFixtures.TIME); var event=new TokenUsageEvent();
        publisher.publish(event); verifyNoInteractions(outbox,budgets);
        assertFalse(publisher.enabled());
        enabled.setEnabled(true); when(budgets.usageEventId(event)).thenReturn("canonical"); publisher.publish(event);
        assertEquals("canonical",event.getEventId()); assertEquals("canonical",event.getKey()); verify(outbox).stage(eq(event),contains("canonical"),any());
        doThrow(new IllegalStateException("database unavailable")).when(outbox).stage(any(),anyString(),any());
        assertThrows(IllegalStateException.class,()->publisher.publish(event)); verify(budgets,never()).settle(any());
    }
}
