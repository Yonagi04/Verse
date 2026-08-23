package com.yonagi.verse.async;

/**
 * 事件 tag 常量。单 topic（verse-event）+ tag 区分事件类型，
 * 新增事件类型 = 新增 tag + 事件 DTO + handler，无需新建 topic / consumer group。
 *
 * @author Yonagi
 */
public final class EventTag {

    public static final String NOTIFICATION = "NOTIFICATION";
    public static final String LOGIN_LOG = "LOGIN_LOG";
    public static final String COUNTER = "COUNTER";
    public static final String TOKEN_USAGE = "TOKEN_USAGE";

    private EventTag() {
    }
}
