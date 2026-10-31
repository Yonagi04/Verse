package com.yonagi.verse.adapter.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

/** 本域多个计数在同一Lua原子检查；键和业务限额由对应分发/查询适配器决定。 */
@Component
@RequiredArgsConstructor
public class SkillRedisRateCounter {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            for i=1,#KEYS do
                if tonumber(redis.call('GET',KEYS[i]) or '0') >= tonumber(ARGV[i]) then return 0 end
            end
            for i=1,#KEYS do
                local n=redis.call('INCR',KEYS[i])
                if n==1 then redis.call('EXPIRE',KEYS[i],60) end
            end
            return 1
            """, Long.class);
    private final StringRedisTemplate redisTemplate;

    /**
     * @param keys 本域固定前缀及实际主体摘要，同一hash slot
     * @param limits 对应每个计数的60秒窗口上限
     */
    public void check(List<String> keys, String... limits) {
        Long accepted;
        try {
            accepted = redisTemplate.execute(LIMIT, keys, (Object[]) limits);
        } catch (DataAccessException exception) {
            throw new ServerException("Skill访问保护暂时不可用", SkillErrorCodeEnum.UNAVAILABLE);
        }
        if (accepted == null) {
            throw new ServerException("Skill访问保护未返回有效结果", SkillErrorCodeEnum.UNAVAILABLE);
        }
        if (!Long.valueOf(1L).equals(accepted)) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
    }
}
