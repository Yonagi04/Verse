package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.*;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.projection.SkillCommentResourceProjection;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillCommentNotificationServiceTest {
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SkillCommentNotificationService service = new SkillCommentNotificationService(accounts, notifications);
    private SkillCommentResourceProjection resource() {
        var resource = new SkillCommentResourceProjection();
        resource.setOwnerUserId(9L); resource.setName("测试Skill");
        when(accounts.isAccountActive(9L)).thenReturn(true);
        when(accounts.isAccountActive(8L)).thenReturn(true);
        when(accounts.readActiveDisplayName(2L)).thenReturn("张三");
        return resource;
    }
    @Test void parentAndNestedReplyHaveOnlyTheirDirectRecipientAndFullText() {
        var resource = resource();
        service.notifyCreated(2L, resource, null, "完整评论\n第二行");
        verify(notifications).createAndPush(null, "SYSTEM", "INFO", "Skill收到新评论",
                "张三 评论了你的Skill《测试Skill》：\n完整评论\n第二行", null, List.of(9L));
        var target = SkillCommentCommandServiceTest.row(101L, 100L, 8L);
        service.notifyCreated(2L, resource, target, "子回复");
        verify(notifications).createAndPush(null, "SYSTEM", "INFO", "评论收到新回复",
                "张三 回复了你的评论：\n子回复", null, List.of(8L));
        verifyNoMoreInteractions(notifications);
    }
    @Test void selfAndInactiveRecipientDoNotNotify() {
        var resource = resource();
        service.notifyCreated(9L, resource, null, "自己");
        when(accounts.isAccountActive(9L)).thenReturn(false);
        service.notifyCreated(2L, resource, null, "关闭");
        verifyNoInteractions(notifications);
    }
    @Test void missingActorAndNotificationDomainFailureBecomeSkillDependencyFailure() {
        var resource = resource();
        when(accounts.readActiveDisplayName(2L)).thenReturn(null);
        assertThrows(ServerException.class, () -> service.notifyCreated(2L, resource, null, "评论"));
        when(accounts.readActiveDisplayName(2L)).thenReturn("张三");
        doThrow(new ClientException(NotificationErrorCodeEnum.NOTIFICATION_NOT_FOUND)).when(notifications)
                .createAndPush(any(), any(), any(), any(), any(), any(), any());
        assertEquals(SkillErrorCodeEnum.UNAVAILABLE.code(), assertThrows(ServerException.class,
                () -> service.notifyCreated(2L, resource, null, "评论")).getErrorCode());
    }
}
