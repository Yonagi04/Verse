package com.yonagi.verse.service.skillhub.port;

/** 匿名分发的主体/IP/全局限流边界；限流失效不能退化为无限制分发。 */
public interface SkillDownloadRateLimiter {
    /**
     * @param subjectHash 只用于限流的非明文主体摘要
     * @param ipHash 实际连接IP摘要
     */
    void check(String subjectHash, String ipHash);
}
