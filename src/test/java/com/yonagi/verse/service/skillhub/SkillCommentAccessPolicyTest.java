package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dao.mapper.skillhub.*;
import com.yonagi.verse.dao.projection.SkillCommentResourceProjection;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillCommentAccessPolicyTest {
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillCommentAccessMapper resources = mock(SkillCommentAccessMapper.class);
    private final SkillCommentMapper comments = mock(SkillCommentMapper.class);
    private final SkillCommentAccessPolicy policy = new SkillCommentAccessPolicy(accounts, resources, comments);
    private SkillCommentResourceProjection ref;
    private SkillPublisherDO publisher;
    private SkillDO skill;
    private SkillVersionDO version;
    private SkillArtifactDO artifact;

    @BeforeEach void setup() {
        ref = new SkillCommentResourceProjection();
        ref.setSkillId(20L); ref.setPublisherId(30L); ref.setOwnerUserId(9L);
        when(resources.reference(20L)).thenReturn(ref); when(resources.available(20L)).thenReturn(ref);
        when(accounts.lockActiveAccountExclusively(any())).thenReturn(true);
        when(accounts.lockActiveAccount(any())).thenReturn(true);
        publisher = new SkillPublisherDO(); publisher.setUserId(9L); publisher.setStatus("ACTIVE");
        skill = new SkillDO(); skill.setSkillId(20L); skill.setPublisherId(30L); skill.setStatus("ACTIVE");
        skill.setCurrentVersionId(40L); skill.setPublicRevisionId(50L);
        version = new SkillVersionDO(); version.setSkillId(20L); version.setArtifactId(60L); version.setStatus("AVAILABLE");
        artifact = new SkillArtifactDO(); artifact.setOwnerUserId(9L); artifact.setStatus("READY");
        when(resources.lockPublisher(30L)).thenReturn(publisher); when(resources.lockSkill(20L)).thenReturn(skill);
        when(resources.lockVersion(40L)).thenReturn(version); when(resources.lockArtifact(60L)).thenReturn(artifact);
    }
    @Test void locksAccountsInIdOrderAndDoesNotRejectInactiveOwnerBeforeReplay() {
        var before = policy.lockAccounts(2L, 20L);
        var order = inOrder(accounts);
        order.verify(accounts).lockActiveAccountExclusively(2L);
        order.verify(accounts).lockActiveAccount(9L);
        assertTrue(before.ownerActive()); assertSame(ref, policy.lockAvailable(before));
        reset(accounts);
        when(accounts.lockActiveAccount(9L)).thenReturn(false);
        when(accounts.lockActiveAccountExclusively(10L)).thenReturn(true);
        var after = policy.lockAccounts(10L, 20L);
        var other = inOrder(accounts);
        other.verify(accounts).lockActiveAccount(9L);
        other.verify(accounts).lockActiveAccountExclusively(10L);
        assertFalse(after.ownerActive());
        assertThrows(ClientException.class, () -> policy.lockAvailable(after));
    }
    @Test void selfOwnerUsesSingleExclusiveLockAndMissingOrInactiveActorIsDenied() {
        var locked = policy.lockAccounts(9L, 20L);
        assertTrue(locked.ownerActive()); verify(accounts, never()).lockActiveAccount(any());
        when(resources.reference(20L)).thenReturn(null);
        assertNull(policy.lockAccounts(2L, 20L).reference());
        assertThrows(ClientException.class, () -> policy.lockAvailable(new SkillCommentAccessPolicy.LockedAccounts(null, true)));
        when(accounts.lockActiveAccountExclusively(2L)).thenReturn(false);
        assertThrows(ClientException.class, () -> policy.lockActor(2L));
        assertThrows(ClientException.class, () -> policy.lockActor(null));
        assertThrows(ClientException.class, () -> policy.lockAccounts(2L, 0L));
    }
    @Test void everyLifecycleLayerAndRelationshipIsRechecked() {
        var locked = new SkillCommentAccessPolicy.LockedAccounts(ref, true);
        publisher.setStatus("CLOSED"); assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
        publisher.setStatus("ACTIVE"); publisher.setUserId(8L);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); publisher.setUserId(9L);
        when(resources.lockPublisher(30L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
        when(resources.lockPublisher(30L)).thenReturn(publisher);
        skill.setStatus("OWNER_UNPUBLISHED"); assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
        skill.setStatus("ACTIVE"); skill.setPublisherId(99L);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); skill.setPublisherId(30L);
        skill.setCurrentVersionId(null); assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
        skill.setCurrentVersionId(40L); skill.setPublicRevisionId(null);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); skill.setPublicRevisionId(50L);
        when(resources.lockSkill(20L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); when(resources.lockSkill(20L)).thenReturn(skill);
        version.setStatus("BLOCKED"); assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
        version.setStatus("AVAILABLE"); version.setSkillId(99L);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); version.setSkillId(20L);
        when(resources.lockVersion(40L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); when(resources.lockVersion(40L)).thenReturn(version);
        artifact.setStatus("DELETED"); assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
        artifact.setStatus("READY"); artifact.setOwnerUserId(99L);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); artifact.setOwnerUserId(9L);
        when(resources.lockArtifact(60L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked)); when(resources.lockArtifact(60L)).thenReturn(artifact);
        when(resources.available(20L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockAvailable(locked));
    }
    @Test void readRequiresActiveAccountAndAvailableSkill() {
        assertThrows(ClientException.class, () -> policy.read(2L, 20L));
        when(accounts.isAccountActive(2L)).thenReturn(true);
        assertSame(ref, policy.read(2L, 20L));
        when(resources.available(20L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.read(2L, 20L));
    }
    @Test void targetsMustBelongToSkillAndRootLocksBeforeChild() {
        var root = SkillCommentCommandServiceTest.row(100, 0, 9);
        var child = SkillCommentCommandServiceTest.row(101, 100, 2);
        when(comments.selectById(101L)).thenReturn(child);
        when(comments.lock(20L, 100L)).thenReturn(root); when(comments.lock(20L, 101L)).thenReturn(child);
        assertSame(child, policy.lockComment(20L, 101L).target());
        var order = inOrder(comments);
        order.verify(comments).selectById(101L); order.verify(comments).lock(20L, 100L); order.verify(comments).lock(20L, 101L);
        when(comments.selectById(100L)).thenReturn(root);
        assertSame(root, policy.lockComment(20L, 100L).root());
        child.setSkillId(99L); assertThrows(ClientException.class, () -> policy.lockComment(20L, 101L));
        child.setSkillId(20L); root.setRootId(500L);
        assertThrows(ClientException.class, () -> policy.lockComment(20L, 101L));
        root.setRootId(0L); when(comments.lock(20L, 100L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockComment(20L, 101L));
        when(comments.selectById(404L)).thenReturn(null);
        assertThrows(ClientException.class, () -> policy.lockComment(20L, 404L));
    }
}
