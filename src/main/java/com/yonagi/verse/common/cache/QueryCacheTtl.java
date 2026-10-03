package com.yonagi.verse.common.cache;

import java.util.concurrent.TimeUnit;

/** 已确认的业务缓存基础有效期；实际写入增加 0–20% 抖动。 */
public final class QueryCacheTtl {
    private QueryCacheTtl() { }
    /** 通知和用量报表，以及不存在结果：至少十分钟。 */
    public static final long MINUTES_10 = TimeUnit.MINUTES.toSeconds(10);
    /** 概览、动态、邀请、申请与审计列表：三十分钟。 */
    public static final long MINUTES_30 = TimeUnit.MINUTES.toSeconds(30);
    /** 登录历史、邀请码详情与报表筛选项：一小时。 */
    public static final long HOURS_1 = TimeUnit.HOURS.toSeconds(1);
    /** 租户关系、模型配置与预设：四小时。 */
    public static final long HOURS_4 = TimeUnit.HOURS.toSeconds(4);
    /** 用户资料：六小时。 */
    public static final long HOURS_6 = TimeUnit.HOURS.toSeconds(6);
    /** 模型标签：十二小时。 */
    public static final long HOURS_12 = TimeUnit.HOURS.toSeconds(12);
    /** 通知正文与审计详情：二十四小时。 */
    public static final long HOURS_24 = TimeUnit.HOURS.toSeconds(24);
}
