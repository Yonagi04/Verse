package com.yonagi.verse.service.skillhub.port;

/** 公共SQL读取的IP及实例总预算，不缓存查询结果或授权。 */
public interface SkillPublicQueryRateLimiter {
    /** @param ipHash 实际连接IP摘要，不信任客户端转发头 */
    void check(String ipHash);
}
