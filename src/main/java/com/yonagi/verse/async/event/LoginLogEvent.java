package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 登录事件：登录成功/失败时投递（普通消息），消费者按 {@link #result} 分叉落库
 * （成功→历史+设备，失败→仅历史）。
 *
 * @author Yonagi
 */
@Getter
@Setter
@NoArgsConstructor
public class LoginLogEvent extends DomainEvent {

    /**
     * 用户 ID（同时作为顺序键 key）
     */
    private Long userId;

    private String deviceId;

    private String deviceName;

    private String ip;

    private String region;

    /**
     * 登录结果：成功 / 失败
     */
    private String result;

    private String failReason;

    /**
     * 登录时间（生产者生成，保证历史时间准确）
     */
    private Date loginTime;

    @Override
    public String eventType() {
        return EventTag.LOGIN_LOG;
    }
}
