package com.yonagi.verse.common.cache;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/** v2 控制记录的原子操作；脚本运行错误不代表已执行修改被回滚。 */
final class QueryScopedScripts {
    private static final String TYPES = """
            local types = {'string','set','zset','hash','zset','zset','zset'}
            for i=1,7 do
                local actual = redis.call('TYPE',KEYS[i]).ok
                if actual ~= 'none' and actual ~= types[i] then
                    return redis.error_reply('invalid query cache control type')
                end
            end
            """;
    private static final String EMPTY_CONDITION = """
            if redis.call('SCARD',KEYS[2]) == 0 and redis.call('ZCARD',KEYS[3]) == 0
                and redis.call('HLEN',KEYS[4]) == 0 and redis.call('ZCARD',KEYS[5]) == 0 then
                redis.call('ZREM',KEYS[7],ARGV[3])
            """;

    static final DefaultRedisScript<Long> BEGIN = new DefaultRedisScript<>(TYPES + """
            if redis.replicate_commands then redis.replicate_commands() end
            local time = redis.call('TIME')
            local now = time[1]*1000+math.floor(time[2]/1000)
            -- 注册先于 writer；后续写失败仍保留可发现的恢复入口。
            redis.call('ZADD',KEYS[7],'NX',now,ARGV[4])
            redis.call('SET',KEYS[1],ARGV[1])
            redis.call('SADD',KEYS[2],ARGV[2])
            redis.call('ZADD',KEYS[3],'NX',now,ARGV[2])
            redis.call('HSET',KEYS[4],ARGV[2],ARGV[3])
            return 1
            """, Long.class);

    static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>(TYPES + """
            if redis.call('SISMEMBER',KEYS[2],ARGV[2]) == 1 then
                if not redis.call('ZSCORE',KEYS[5],ARGV[2]) then return 0 end
                redis.call('SET',KEYS[1],ARGV[1])
                redis.call('SREM',KEYS[2],ARGV[2])
            end
            redis.call('ZREM',KEYS[3],ARGV[2])
            redis.call('HDEL',KEYS[4],ARGV[2])
            redis.call('ZREM',KEYS[5],ARGV[2])
            """ + EMPTY_CONDITION + "end\nreturn 1", Long.class);

    static final DefaultRedisScript<Long> REMOVE_EMPTY_REGISTRATION = new DefaultRedisScript<>(
            TYPES + EMPTY_CONDITION + "return 1\nend\nreturn 0", Long.class);

    static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.replicate_commands then redis.replicate_commands() end
            local time = redis.call('TIME')
            redis.call('ZADD',KEYS[1],'XX',time[1]*1000+math.floor(time[2]/1000),ARGV[1])
            return 1
            """, Long.class);

    private QueryScopedScripts() { }
}
