package com.yonagi.verse.service.skillhub.port;

/** 私有不可变对象边界；禁止返回公开URL或预签名绕过实时分发校验。 */
public interface SkillArtifactStore {
    /**
     * @param key 服务端生成的本域对象Key
     * @param bytes 有界原始字节
     */
    void put(String key, byte[] bytes);

    /**
     * @param key 数据库中已授权对象Key
     * @param maximumBytes 实际读取上限
     * @return 有界字节
     */
    byte[] get(String key, int maximumBytes);

    /**
     * @param key 经数据库引用复核后的本域对象Key；不存在同样视为成功
     */
    void delete(String key);

    /**
     * @param cursor 上次返回的分页游标，首次null
     * @return 本域前缀内最多500个候选及下一游标
     */
    SkillObjectPage list(String cursor);
}
