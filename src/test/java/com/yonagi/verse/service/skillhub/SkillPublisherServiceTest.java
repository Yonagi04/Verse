package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublisherMapper;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillPublisherServiceTest {
    private final SkillPublisherMapper mapper = mock(SkillPublisherMapper.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillPublisherService service = new SkillPublisherService(mapper, accounts);

    @Test
    void firstDraftAssociatesExistingAccountWithoutSeparateIdentityInput() {
        when(accounts.lockActiveAccountExclusively(1L)).thenReturn(true);
        when(accounts.readActiveDisplayName(1L)).thenReturn("现有账号昵称");
        SkillPublisherDO result = service.ensure(1L);
        ArgumentCaptor<SkillPublisherDO> saved = ArgumentCaptor.forClass(SkillPublisherDO.class);
        verify(mapper).insert(saved.capture());
        assertSame(result, saved.getValue());
        assertEquals(1L, result.getUserId());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals("u_1", result.getHandle());
        assertEquals("现有账号昵称", result.getPublicName());
        org.mockito.InOrder locks = inOrder(accounts, mapper);
        locks.verify(accounts).lockActiveAccountExclusively(1L);
        locks.verify(mapper).selectByUserIdForUpdate(1L);
    }

    @Test
    void existingAssociationKeepsStableIdAndHandleButCannotReopenClosedAccount() {
        when(accounts.lockActiveAccountExclusively(1L)).thenReturn(true);
        SkillPublisherDO publisher = SkillTestFixture.publisher();
        when(mapper.selectByUserIdForUpdate(1L)).thenReturn(publisher);
        assertSame(publisher, service.ensure(1L));
        assertEquals(10L, publisher.getPublisherId());
        assertEquals("author", publisher.getHandle());
        publisher.setStatus("CLOSED");
        assertThrows(ClientException.class, () -> service.ensure(1L));
        verify(mapper, never()).insert(any(SkillPublisherDO.class));
    }

    @Test
    void inactiveUserIsRejectedBeforeReadingAssociation() {
        assertThrows(ClientException.class, () -> service.ensure(1L));
        verifyNoInteractions(mapper);
    }

    @Test
    void absentRealAccountNameCannotCreateInventedIdentity() {
        when(accounts.lockActiveAccountExclusively(1L)).thenReturn(true);
        assertThrows(ServerException.class, () -> service.ensure(1L));
        when(accounts.readActiveDisplayName(1L)).thenReturn("  ");
        assertThrows(ServerException.class, () -> service.ensure(1L));
        verify(mapper, never()).insert(any(SkillPublisherDO.class));
    }
}
