-- 错误凭证不能删除正确凭证；只允许未过期且摘要匹配的凭证被消费一次。
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'string' then return -1 end
if not ARGV[1] or string.len(ARGV[1]) ~= 32 then return -1 end
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('PTTL', KEYS[1]) <= 0 then return 0 end
redis.call('DEL', KEYS[1])
return 1
