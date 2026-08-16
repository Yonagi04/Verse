package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.LoginLogEvent;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.dao.entity.LoginHistoryDO;
import com.yonagi.verse.dao.mapper.LoginHistoryMapper;
import com.yonagi.verse.service.LoginDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 登录事件消费者：按 result 分叉落库（成功→历史+设备，失败→仅历史），
 * 以 event_id 作为登录历史幂等键，并在事务提交后失效该用户的登录历史缓存。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginLogEventHandler implements DomainEventHandler<LoginLogEvent> {

    private static final String RESULT_SUCCESS = "成功";

    private final LoginHistoryMapper loginHistoryMapper;
    private final LoginDeviceService loginDeviceService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String eventType() {
        return EventTag.LOGIN_LOG;
    }

    @Override
    public Class<LoginLogEvent> eventClass() {
        return LoginLogEvent.class;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(LoginLogEvent event) {
        LoginHistoryDO history = LoginHistoryDO.builder()
                .userId(event.getUserId())
                .loginTime(event.getLoginTime())
                .deviceName(event.getDeviceName())
                .ip(event.getIp())
                .region(event.getRegion())
                .result(event.getResult())
                .failReason(event.getFailReason())
                .eventId(event.getEventId())
                .build();
        try {
            loginHistoryMapper.insert(history);
        } catch (DuplicateKeyException e) {
            log.info("[login-log] 重复登录事件，跳过: eventId={}", event.getEventId());
            return;
        }

        if (RESULT_SUCCESS.equals(event.getResult())) {
            loginDeviceService.upsertLoginDevice(event.getUserId(), event.getDeviceId(),
                    event.getDeviceName(), event.getIp(), event.getRegion());
        }

        Long userId = event.getUserId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateLoginHistoryCache(userId);
            }
        });
    }

    private void invalidateLoginHistoryCache(Long userId) {
        String pattern = RedisKeyConstant.USER_LOGIN_HISTORY_KEY + userId + ":*";
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            Set<String> keys = new HashSet<>();
            cursor.forEachRemaining(keys::add);
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("[login-log] 登录历史缓存失效失败: userId={}", userId, e);
        }
    }
}
