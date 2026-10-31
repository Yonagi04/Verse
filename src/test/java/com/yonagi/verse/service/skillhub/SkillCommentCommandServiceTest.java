package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.*;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dao.mapper.skillhub.*;
import com.yonagi.verse.dao.projection.SkillCommentResourceProjection;
import com.yonagi.verse.dto.req.SkillCommentCreateReqDTO;
import com.yonagi.verse.dto.resp.SkillCommentCreatedRespDTO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 评论用例行为测试；不把Mock当作真实事务与锁的证明。 */
class SkillCommentCommandServiceTest {
    private final SkillCommentAccessPolicy access = mock(SkillCommentAccessPolicy.class);
    private final SkillCommentMapper comments = mock(SkillCommentMapper.class);
    private final SkillCommentReactionMapper reactions = mock(SkillCommentReactionMapper.class);
    private final SkillIdempotencyService keys = mock(SkillIdempotencyService.class);
    private final SkillCommentNotificationService notifications = mock(SkillCommentNotificationService.class);
    private final SkillCommentCommandService service =
            new SkillCommentCommandService(access, comments, reactions, keys, notifications);
    private SkillCommentDO root;
    private SkillCommentDO child;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "comments"), SkillCommentDO.class);
        root = row(100, 0, 9); root.setReplyCount(10L);
        child = row(101, 100, 2);
        when(access.lockAccounts(2L, 20L)).thenReturn(new SkillCommentAccessPolicy.LockedAccounts(null, true));
        SkillCommentResourceProjection resource = new SkillCommentResourceProjection();
        resource.setSkillId(20L); resource.setOwnerUserId(9L); resource.setName("测试Skill");
        when(access.lockAvailable(any())).thenReturn(resource);
        when(access.lockComment(20L, 100L)).thenReturn(new SkillCommentAccessPolicy.LockedComment(root, root));
        when(access.lockComment(20L, 101L)).thenReturn(new SkillCommentAccessPolicy.LockedComment(root, child));
        when(comments.insert(any(SkillCommentDO.class))).thenReturn(1);
        when(comments.update(isNull(), any())).thenReturn(1);
        when(reactions.insert(any(SkillCommentReactionDO.class))).thenReturn(1);
        when(reactions.updateById(any())).thenReturn(1);
        when(reactions.deleteById(any(Long.class))).thenReturn(1);
    }

    @Test
    void sendsNormalizedParentAndStoresOnlyIdReceipt() {
        var result = service.create(2L, 20L, new SkillCommentCreateReqDTO("  多行\r\n评论  ", null), "key");
        assertTrue(result.created());
        assertEquals(result.receipt().commentId(), result.receipt().rootId());
        assertEquals("20", result.receipt().skillId());
        var captured = org.mockito.ArgumentCaptor.forClass(SkillCommentDO.class);
        verify(comments).insert(captured.capture());
        assertEquals("多行\n评论", captured.getValue().getContent());
        assertEquals(0L, captured.getValue().getRootId());
        assertEquals(2L, captured.getValue().getAuthorUserId());
        verify(notifications).notifyCreated(eq(2L), any(), isNull(), eq("多行\n评论"));
        verify(keys).record(eq(2L), eq("COMMENT_CREATE"), eq("key"), any(), any(), eq(result.receipt()));
    }

    @Test
    void nestedReplyKeepsRealTargetAndRootAndUpdatesReplyCount() {
        var result = service.create(2L, 20L, new SkillCommentCreateReqDTO("子回复", 101L), "key");
        assertEquals("100", result.receipt().rootId());
        var captured = org.mockito.ArgumentCaptor.forClass(SkillCommentDO.class);
        verify(comments).insert(captured.capture());
        assertEquals(100L, captured.getValue().getRootId());
        assertEquals(101L, captured.getValue().getReplyToCommentId());
        verify(notifications).notifyCreated(eq(2L), any(), same(child), eq("子回复"));
        verify(comments).update(isNull(), argThat(wrapper -> {
            var update = (LambdaUpdateWrapper<SkillCommentDO>) wrapper;
            update.getSqlSet();
            return update.getParamNameValuePairs().containsValue(11L);
        }));
    }

    @Test
    void originalReceiptReplaysEvenWhenOwnerIsNoLongerAvailable() {
        var receipt = new SkillCommentCreatedRespDTO("201", "201", "20");
        when(keys.replay(eq(2L), eq("COMMENT_CREATE"), eq("key"), any(), eq(SkillCommentCreatedRespDTO.class)))
                .thenReturn(receipt);
        var result = service.create(2L, 20L, new SkillCommentCreateReqDTO("评论", null), "key");
        assertFalse(result.created()); assertEquals(receipt, result.receipt());
        verify(access, never()).lockAvailable(any());
        verifyNoInteractions(comments, notifications);
    }

    @Test
    void deletedTargetRejectsWithoutCreatingNotification() {
        child.setDelFlag(1);
        var error = assertThrows(ClientException.class,
                () -> service.create(2L, 20L, new SkillCommentCreateReqDTO("回复", 101L), "key"));
        assertEquals(SkillErrorCodeEnum.CONFLICT.code(), error.getErrorCode());
        verify(comments, never()).insert(any(SkillCommentDO.class));
        verifyNoInteractions(notifications);
    }

    @Test
    void contentRejectsBlankOversizeAndControlButAllowsThousandEmoji() {
        for (String content : new String[]{" ", "a".repeat(1001), "\u0000"}) {
            assertThrows(ClientException.class, () ->
                    service.create(2L, 20L, new SkillCommentCreateReqDTO(content, null), "key"));
        }
        assertThrows(ClientException.class, () -> service.create(2L, 20L, null, "key"));
        assertThrows(ClientException.class, () ->
                service.create(2L, 20L, new SkillCommentCreateReqDTO("内容", 0L), "key"));
        var result = service.create(2L, 20L, new SkillCommentCreateReqDTO("😀".repeat(1000), null), "key");
        assertTrue(result.created());
    }

    @Test
    void notificationFailureDoesNotProduceSuccessOrIdempotencyReceipt() {
        doThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE)).when(notifications)
                .notifyCreated(any(), any(), any(), anyString());
        assertThrows(ServerException.class, () ->
                service.create(2L, 20L, new SkillCommentCreateReqDTO("评论", null), "key"));
        verify(keys, never()).record(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"NONE,LIKE,1", "NONE,DISLIKE,0", "NONE,NONE,0", "LIKE,LIKE,0",
            "LIKE,DISLIKE,-1", "LIKE,NONE,-1", "DISLIKE,LIKE,1", "DISLIKE,NONE,0", "DISLIKE,DISLIKE,0"})
    void reactionTransitionsSetDesiredStateWithoutDoubleCounting(String old, String desired, long delta) {
        child.setLikeCount(5L);
        if (!"NONE".equals(old)) {
            SkillCommentReactionDO previous = new SkillCommentReactionDO();
            previous.setId(900L); previous.setReaction(old);
            when(reactions.selectOne(any())).thenReturn(previous);
        }
        var result = service.react(2L, 20L, 101L, desired);
        assertEquals(desired, result.reaction());
        assertEquals(5 + delta, result.likeCount());
        if (old.equals(desired)) verify(comments, never()).update(isNull(), any());
    }

    @Test
    void reactionsRejectUnknownDeletedAndCorruptNegativeCount() {
        assertThrows(ClientException.class, () -> service.react(2L, 20L, 101L, null));
        assertThrows(ClientException.class, () -> service.react(2L, 20L, 101L, "OTHER"));
        child.setDelFlag(1);
        assertThrows(ClientException.class, () -> service.react(2L, 20L, 101L, "LIKE"));
        child.setDelFlag(0);
        SkillCommentReactionDO previous = new SkillCommentReactionDO();
        previous.setId(900L); previous.setReaction("LIKE");
        when(reactions.selectOne(any())).thenReturn(previous);
        assertThrows(ServerException.class, () -> service.react(2L, 20L, 101L, "NONE"));
    }

    @Test
    void onlyOwnCommentCanBeDeletedAndRepeatedDeleteDoesNotDecrementAgain() {
        var error = assertThrows(ClientException.class, () -> service.delete(2L, 20L, 100L));
        assertEquals(SkillErrorCodeEnum.NOT_ACCESSIBLE.code(), error.getErrorCode());
        verify(comments, never()).update(isNull(), any());
        service.delete(2L, 20L, 101L);
        verify(comments, times(2)).update(isNull(), any());
        child.setDelFlag(1);
        service.delete(2L, 20L, 101L);
        verify(comments, times(2)).update(isNull(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void rootDeletionDoesNotDecrementOthersRepliesAndWriteFailureIsNotSuccess() {
        root.setAuthorUserId(2L);
        service.delete(2L, 20L, 100L);
        verify(comments).update(isNull(), any());
        when(comments.insert(any(SkillCommentDO.class))).thenReturn(0);
        assertThrows(ServerException.class, () ->
                service.create(2L, 20L, new SkillCommentCreateReqDTO("评论", null), "key"));
    }

    static SkillCommentDO row(long id, long rootId, long author) {
        SkillCommentDO row = new SkillCommentDO();
        row.setCommentId(id); row.setSkillId(20L); row.setRootId(rootId); row.setAuthorUserId(author);
        row.setDelFlag(0); row.setLikeCount(0L); row.setReplyCount(0L);
        row.setContent("评论内容"); row.setCreateTime(LocalDateTime.of(2026, 10, 31, 1, 0));
        return row;
    }
}
