package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillCommentDO;
import com.yonagi.verse.dao.projection.SkillCommentResourceProjection;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 评论通知收件人与纯文本内容规则，持久化复用通知域公开事务入口。 */
@Service
@RequiredArgsConstructor
public class SkillCommentNotificationService {
    private final AccountAvailabilityService accounts;
    private final NotificationService notifications;

    /** 调用方持有评论事务；最多创建一个收件人的系统通知，持久化失败传播回滚。 */
    public void notifyCreated(Long actorId, SkillCommentResourceProjection resource,
                              SkillCommentDO target, String content) {
        Long recipient = target == null ? resource.getOwnerUserId() : target.getAuthorUserId();
        if (actorId.equals(recipient) || !accounts.isAccountActive(recipient)) {
            return;
        }
        String displayName = accounts.readActiveDisplayName(actorId);
        if (displayName == null) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        String title = target == null ? "Skill收到新评论" : "评论收到新回复";
        String object = target == null ? " 评论了你的Skill《" + resource.getName() + "》：" : " 回复了你的评论：";
        try {
            notifications.createAndPush(null, "SYSTEM", "INFO", title, displayName + object + "\n" + content,
                    null, List.of(recipient));
        } catch (AbstractException failure) {
            throw new ServerException("评论通知无法保存", failure, SkillErrorCodeEnum.UNAVAILABLE);
        }
    }
}
