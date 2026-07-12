package com.yonagi.verse.common.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

/**
 * 雪花算法 ID 生成工具类
 *
 * @author Yonagi
 */
public class SnowflakeIdUtil {

    private static final Snowflake SNOWFLAKE;

    static {
        // workerId 和 datacenterId 后续可从配置中读取，支持分布式部署
        SNOWFLAKE = IdUtil.getSnowflake(1, 1);
    }

    /**
     * 生成唯一业务 ID（如 user_id、tenant_id 等）
     */
    public static long nextId() {
        return SNOWFLAKE.nextId();
    }
}
