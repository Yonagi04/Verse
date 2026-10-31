package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublisherMapper;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillAccessPolicyTest {
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillPublisherMapper publishers = mock(SkillPublisherMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final SkillDraftMapper drafts = mock(SkillDraftMapper.class);
    private final SkillAccessPolicy policy = new SkillAccessPolicy(accounts, publishers, skills, drafts);
    private final SkillPublisherDO publisher = SkillTestFixture.publisher();
    private final SkillDO skill = SkillTestFixture.skill();
    private final SkillDraftDO draft = SkillTestFixture.draft();

    @BeforeEach
    void owner() {
        SkillTestFixture.initializeMapping(SkillDraftDO.class);
        when(accounts.isAccountActive(1L)).thenReturn(true);
        when(accounts.lockActiveAccount(1L)).thenReturn(true);
        when(publishers.selectById(10L)).thenReturn(publisher);
        when(publishers.selectByUserIdForUpdate(1L)).thenReturn(publisher);
        when(skills.selectById(20L)).thenReturn(skill);
        when(skills.selectByIdForUpdate(20L)).thenReturn(skill);
        when(drafts.selectOne(any())).thenReturn(draft);
        when(drafts.selectByIdForUpdate(30L)).thenReturn(draft);
    }

    @Test
    void personalOwnerCanReadAndLockWithoutAnyTenantRole() {
        assertEquals(30L, policy.readDraft(1L, 30L).draft().getDraftId());
        assertEquals(20L, policy.lockDraft(1L, 30L).skill().getSkillId());
        InOrder sequence = inOrder(accounts, publishers, skills, drafts);
        sequence.verify(accounts).lockActiveAccount(1L);
        sequence.verify(publishers).selectByUserIdForUpdate(1L);
        sequence.verify(drafts).selectOne(any());
        sequence.verify(skills).selectByIdForUpdate(20L);
        sequence.verify(drafts).selectByIdForUpdate(30L);
    }

    @Test
    void unavailableAccountAndClosedPublisherCannotReachResourceLocks() {
        assertThrows(ClientException.class, () -> policy.lockDraft(2L, 30L));
        verify(publishers, never()).selectByUserIdForUpdate(2L);
        publisher.setStatus("CLOSED");
        assertThrows(ClientException.class, () -> policy.readDraft(1L, 30L));
        assertThrows(ClientException.class, () -> policy.lockSkill(1L, 20L));
        verify(skills, never()).selectByIdForUpdate(20L);
    }

    @Test
    void rejectsCrossPublisherAndMismatchedDraftRelationship() {
        skill.setPublisherId(11L);
        assertThrows(ClientException.class, () -> policy.lockSkill(1L, 20L));
        assertThrows(ClientException.class, () -> policy.lockDraft(1L, 30L));
        skill.setPublisherId(10L);
        draft.setOwnerUserId(2L);
        assertThrows(ClientException.class, () -> policy.lockDraft(1L, 30L));
        assertEquals("EDITING", draft.getStatus());
        verify(drafts, never()).updateById(any());
    }

    @Test
    void missingDraftAndMissingParentDoNotExposeOtherResources() {
        when(drafts.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.readDraft(1L, 30L));
        assertThrows(ClientException.class, () -> policy.lockDraft(1L, 30L));
        when(drafts.selectOne(any())).thenReturn(draft);
        when(skills.selectById(20L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.readDraft(1L, 30L));
    }

    @Test
    void currentOwnerAndParentMustStillMatchAfterLocking() {
        when(skills.selectByIdForUpdate(20L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockSkill(1L, 20L));
        assertThrows(ClientException.class, () -> policy.lockDraft(1L, 30L));
        when(skills.selectByIdForUpdate(20L)).thenReturn(skill);
        when(drafts.selectByIdForUpdate(30L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockDraft(1L, 30L));
        when(drafts.selectByIdForUpdate(30L)).thenReturn(draft);
        draft.setSkillId(21L);
        assertThrows(ClientException.class, () -> policy.lockDraft(1L, 30L));
        verify(drafts, never()).updateById(any());
    }

    @Test
    void closedAccountMissingPublisherAndReassignedPublisherCannotReadDraft() {
        when(accounts.isAccountActive(1L)).thenReturn(false);
        assertThrows(ClientException.class, () -> policy.readDraft(1L, 30L));
        when(accounts.isAccountActive(1L)).thenReturn(true);
        when(publishers.selectById(10L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.readDraft(1L, 30L));
        when(publishers.selectById(10L)).thenReturn(publisher);
        publisher.setUserId(2L);
        assertThrows(ClientException.class, () -> policy.readDraft(1L, 30L));
        assertThrows(ClientException.class, () -> policy.lockPublisher(1L));
    }
}
