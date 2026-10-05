package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.UserClosedEvent;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.service.ApiKeyService;
import com.yonagi.verse.service.LlmManageService;
import com.yonagi.verse.service.TenantCrudService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.impl.UserAccountCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 各领域清理独立提交且幂等，失败由 MQ 重试；不依赖注销请求的认证上下文。 */
@Component
@RequiredArgsConstructor
public class UserClosedEventHandler implements DomainEventHandler<UserClosedEvent> {
    private final UserMapper users;
    private final ApiKeyService keys;
    private final UserTenantService memberships;
    private final TenantCrudService tenants;
    private final LlmManageService models;
    private final UserAccountCleanupService cleanup;

    @Override public String eventType() { return EventTag.USER_CLOSED; }
    @Override public Class<UserClosedEvent> eventClass() { return UserClosedEvent.class; }

    @Override
    public void onEvent(UserClosedEvent event) {
        if (event == null || event.getUserId() == null || event.getUserId() <= 0
                || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("注销清理事件缺少用户或事件标识");
        }
        UserDO user = users.selectCleanupState(event.getUserId());
        if (!UserAccountCleanupService.isClosed(user)) throw new IllegalStateException("只能清理已注销账号的资源");
        if (user.getResourceCleanupAt() == null) {
            keys.revokeClosedUsersKeys(event.getUserId());
            memberships.leaveClosedUsersTenants(event.getUserId());
            tenants.deleteClosedUsersPersonalAndSoleMemberTenants(event.getUserId());
            models.deleteClosedUsersServices(event.getUserId());
        }
        cleanup.cleanUsageBatch(event);
    }
}
