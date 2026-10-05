package com.yonagi.verse.async.mq;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.LoginLogEvent;
import com.yonagi.verse.async.event.UserClosedEvent;
import com.yonagi.verse.async.handler.LoginLogEventHandler;
import com.yonagi.verse.async.handler.UserClosedEventHandler;
import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.NotificationRecipientMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.service.*;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import com.yonagi.verse.service.impl.NotificationServiceImpl;
import com.yonagi.verse.service.impl.TenantCrudServiceImpl;
import com.yonagi.verse.service.impl.UserAccountCleanupService;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RocketMQEventPublisherTest {
    // 使用真实注销处理器、租户和通知服务，还原生产循环；仅隔离外部依赖和其他领域服务。
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withAllowCircularReferences(false)
            .withPropertyValues("rocketmq.producer.topic=verse-event", "verse.frontend-baseurl=http://localhost")
            .withUserConfiguration(UserClosedEventHandler.class, TenantCrudServiceImpl.class,
                    NotificationServiceImpl.class, RocketMQEventPublisher.class,
                    EventHandlerRegistry.class, RocketMQConsumerDispatcher.class)
            .withBean(RocketMQTemplate.class, () -> mock(RocketMQTemplate.class))
            .withBean(UserMapper.class, () -> mock(UserMapper.class))
            .withBean(TenantMapper.class, () -> mock(TenantMapper.class))
            .withBean(NotificationMapper.class, () -> mock(NotificationMapper.class))
            .withBean(NotificationRecipientMapper.class, () -> mock(NotificationRecipientMapper.class))
            .withBean(ApiKeyService.class, () -> mock(ApiKeyService.class))
            .withBean(UserTenantService.class, () -> mock(UserTenantService.class))
            .withBean(LlmManageService.class, () -> mock(LlmManageService.class))
            .withBean(UserAccountCleanupService.class, () -> mock(UserAccountCleanupService.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(JwtUtil.class, () -> mock(JwtUtil.class))
            .withBean(TenantValidationHelper.class, () -> mock(TenantValidationHelper.class))
            .withBean(TenantMediaService.class, () -> mock(TenantMediaService.class))
            .withBean(CurrentTenantStateService.class, () -> mock(CurrentTenantStateService.class))
            .withBean(TenantActivityRecorder.class, () -> mock(TenantActivityRecorder.class))
            .withBean(SimpMessagingTemplate.class, () -> mock(SimpMessagingTemplate.class))
            .withBean(QueryCache.class, () -> mock(QueryCache.class))
            .withBean(LoginLogEventHandler.class, () -> {
                LoginLogEventHandler handler = mock(LoginLogEventHandler.class);
                when(handler.eventType()).thenReturn(EventTag.LOGIN_LOG);
                when(handler.eventClass()).thenReturn(LoginLogEvent.class);
                return handler;
            });

    @Test
    void startsAndRegistersUserClosedHandlerWithCircularReferencesDisabled() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(DomainEventPublisher.class);
            assertThat(application.getBean(EventHandlerRegistry.class).get(EventTag.USER_CLOSED))
                    .isSameAs(application.getBean(UserClosedEventHandler.class));
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publishesMqMessageWithTagPayloadAndOrderingKey() {
        context.run(application -> {
            UserClosedEvent event = UserClosedEvent.initial(42L);
            application.getBean(DomainEventPublisher.class).publish(event);

            ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
            verify(application.getBean(RocketMQTemplate.class)).syncSendOrderly(
                    eq("verse-event:" + EventTag.USER_CLOSED), message.capture(), eq(event.getKey()), eq(3000L));
            assertThat(message.getValue().getHeaders().get(MessageConst.PROPERTY_KEYS)).isEqualTo(event.getKey());
            UserClosedEvent payload = JSON.parseObject((String) message.getValue().getPayload(), UserClosedEvent.class);
            assertThat(payload.getEventId()).isEqualTo(event.getEventId());
            assertThat(payload.getUserId()).isEqualTo(42L);
            verifyNoInteractions(application.getBean(UserAccountCleanupService.class));
        });
    }

    @Test
    void dispatcherStillRunsUserClosedCleanup() {
        context.run(application -> {
            UserDO user = new UserDO();
            user.setStatus(2);
            user.setDelFlag(1);
            when(application.getBean(UserMapper.class).selectCleanupState(42L)).thenReturn(user);
            application.getBean(RocketMQConsumerDispatcher.class).onMessage(closedMessage());

            verify(application.getBean(ApiKeyService.class)).revokeClosedUsersKeys(42L);
            verify(application.getBean(UserTenantService.class)).leaveClosedUsersTenants(42L);
            verify(application.getBean(TenantMapper.class)).deleteClosedUsersPersonalAndSoleMemberTenants(42L);
            verify(application.getBean(LlmManageService.class)).deleteClosedUsersServices(42L);
            verify(application.getBean(UserAccountCleanupService.class)).cleanUsageBatch(
                    argThat(event -> event.getUserId().equals(42L)));
        });
    }

    @Test
    void dispatcherPropagatesCleanupFailureForMqRetry() {
        context.run(application -> {
            assertThatThrownBy(() -> application.getBean(RocketMQConsumerDispatcher.class).onMessage(closedMessage()))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(application.getBean(UserAccountCleanupService.class));
        });
    }

    @Test
    void syncFallbackResolvesRegisteredHandlerWithoutSendingMqMessage() {
        context.withPropertyValues("verse.async.login-log.sync-fallback=true").run(application -> {
            LoginLogEvent event = new LoginLogEvent();
            application.getBean(DomainEventPublisher.class).publishInTx(event);
            verify(application.getBean(LoginLogEventHandler.class)).onEvent(event);
            verifyNoMqSend(application.getBean(RocketMQTemplate.class));
        });
    }

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void transactionalDispatchRunsOnlyAfterCommit(boolean fallback, boolean rollback) {
        context.withPropertyValues("verse.async.login-log.sync-fallback=" + fallback).run(application -> {
            var database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2).build();
            try {
                LoginLogEvent event = new LoginLogEvent();
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
                transaction.executeWithoutResult(status -> {
                    application.getBean(DomainEventPublisher.class).publishInTx(event);
                    verifyNoMqSend(application.getBean(RocketMQTemplate.class));
                    verify(application.getBean(LoginLogEventHandler.class), never()).onEvent(any());
                    if (rollback) status.setRollbackOnly();
                });
                if (fallback && !rollback) {
                    verify(application.getBean(LoginLogEventHandler.class)).onEvent(event);
                } else {
                    verify(application.getBean(LoginLogEventHandler.class), never()).onEvent(any());
                }
                if (!fallback && !rollback) {
                    verify(application.getBean(RocketMQTemplate.class)).syncSendOrderly(
                            eq("verse-event:" + EventTag.LOGIN_LOG), any(Message.class), eq(event.getKey()), eq(3000L));
                } else {
                    verifyNoMqSend(application.getBean(RocketMQTemplate.class));
                }
            } finally {
                database.shutdown();
            }
        });
    }

    private void verifyNoMqSend(RocketMQTemplate template) {
        verify(template, never()).syncSendOrderly(anyString(), any(Message.class), anyString(), anyLong());
    }

    private MessageExt closedMessage() {
        MessageExt message = new MessageExt();
        message.setTags(EventTag.USER_CLOSED);
        message.setBody(JSON.toJSONString(UserClosedEvent.initial(42L)).getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
