package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.mapper.skillhub.*;
import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dto.resp.UserPublicProfileRespDTO;
import com.yonagi.verse.service.user.UserPublicProfileService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillCommentQueryServiceTest {
    private final SkillCommentAccessPolicy access = mock(SkillCommentAccessPolicy.class);
    private final SkillCommentMapper comments = mock(SkillCommentMapper.class);
    private final SkillCommentReactionMapper reactions = mock(SkillCommentReactionMapper.class);
    private final UserPublicProfileService profiles = mock(UserPublicProfileService.class);
    private final SkillCommentQueryService service = new SkillCommentQueryService(access, comments, reactions, profiles);

    @Test void emptyAndInvalidPagesKeepStableContract() {
        var empty = service.roots(2L, 20L, "hot", 3, 10);
        assertEquals(3, empty.page()); assertEquals(0L, empty.total()); assertTrue(empty.items().isEmpty());
        verify(comments, never()).roots(any(), anyBoolean(), anyInt(), anyInt());
        assertThrows(ClientException.class, () -> service.roots(2L, 20L, "bad", 1, 10));
        assertThrows(ClientException.class, () -> service.roots(2L, 20L, "new", 0, 10));
        assertThrows(ClientException.class, () -> service.roots(2L, 20L, "new", 1, 51));
        assertThrows(ClientException.class, () -> service.roots(2L, 20L, "new", 10001, 1));
        assertThrows(ClientException.class, () -> service.roots(2L, 20L, "new", 1, 0));
    }

    @Test void rootViewsContainOnlyOwnReactionAndRedactDeletedBodyAndIdentity() {
        var root = SkillCommentCommandServiceTest.row(100, 0, 2);
        root.setLikeCount(7L);
        var deleted = SkillCommentCommandServiceTest.row(200, 0, 9); deleted.setDelFlag(1); deleted.setReplyCount(3L);
        when(comments.countRoots(20L)).thenReturn(2L);
        when(comments.roots(20L, true, 10, 0)).thenReturn(List.of(root, deleted));
        when(profiles.read(Set.of(2L))).thenReturn(Map.of(2L, new UserPublicProfileRespDTO("2", "昵称", null)));
        SkillCommentReactionDO mine = new SkillCommentReactionDO(); mine.setCommentId(100L); mine.setReaction("DISLIKE");
        when(reactions.selectList(any())).thenReturn(List.of(mine));
        var result = service.roots(2L, 20L, "hot", 1, 10);
        assertEquals("DISLIKE", result.items().getFirst().myReaction());
        assertEquals(7L, result.items().getFirst().likeCount());
        assertTrue(result.items().getFirst().canDelete());
        var tombstone = result.items().getLast();
        assertNull(tombstone.content()); assertNull(tombstone.author()); assertFalse(tombstone.canReply());
        assertFalse(tombstone.canDelete()); assertEquals(0L, tombstone.likeCount()); assertEquals(3L, tombstone.replyCount());
    }

    @Test void repliesShowActualTargetWithoutLeakingDeletedText() {
        var root = SkillCommentCommandServiceTest.row(100, 0, 9); root.setDelFlag(1); root.setReplyCount(1L);
        var reply = SkillCommentCommandServiceTest.row(101, 100, 2); reply.setReplyToCommentId(100L);
        when(comments.selectById(100L)).thenReturn(root);
        when(comments.countReplies(20L, 100L)).thenReturn(1L);
        when(comments.replies(20L, 100L, 20, 0)).thenReturn(List.of(reply));
        when(comments.targets(20L, List.of(100L))).thenReturn(List.of(root));
        when(profiles.read(Set.of(2L))).thenReturn(Map.of(2L, new UserPublicProfileRespDTO("2", "昵称", null)));
        when(reactions.selectList(any())).thenReturn(List.of());
        var item = service.replies(3L, 20L, 100L, 1, 20).items().getFirst();
        assertEquals("已删除评论", item.replyTo().displayName()); assertTrue(item.replyTo().deleted());
        assertEquals("NONE", item.myReaction()); assertFalse(item.canDelete()); assertTrue(item.canReply());
        root.setSkillId(99L);
        assertThrows(ClientException.class, () -> service.replies(2L, 20L, 100L, 1, 20));
        root.setSkillId(20L); root.setReplyCount(0L);
        assertThrows(ClientException.class, () -> service.replies(2L, 20L, 100L, 1, 20));
    }

    @Test void liveReplyTargetUsesCurrentProfileAndLatestSortPassesThrough() {
        var root = SkillCommentCommandServiceTest.row(100, 0, 9);
        var reply = SkillCommentCommandServiceTest.row(101, 100, 2); reply.setReplyToCommentId(100L);
        when(comments.selectById(100L)).thenReturn(root);
        when(comments.countReplies(20L, 100L)).thenReturn(1L);
        when(comments.replies(20L, 100L, 20, 0)).thenReturn(List.of(reply));
        when(comments.targets(20L, List.of(100L))).thenReturn(List.of(root));
        when(profiles.read(Set.of(2L, 9L))).thenReturn(Map.of(
                2L, new UserPublicProfileRespDTO("2", "本人", null), 9L, new UserPublicProfileRespDTO("9", "新昵称", null)));
        var item = service.replies(2L, 20L, 100L, 1, 20).items().getFirst();
        assertEquals("新昵称", item.replyTo().displayName()); assertFalse(item.replyTo().deleted());
        when(comments.countRoots(20L)).thenReturn(1L);
        when(comments.roots(20L, false, 5, 5)).thenReturn(List.of());
        assertTrue(service.roots(2L, 20L, "new", 2, 5).items().isEmpty());
        assertThrows(ClientException.class, () -> service.replies(2L, 20L, 0L, 1, 20));
        when(comments.selectById(100L)).thenReturn(reply);
        assertThrows(ClientException.class, () -> service.replies(2L, 20L, 100L, 1, 20));
    }
}
