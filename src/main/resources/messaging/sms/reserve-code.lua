-- 先校验所有类型，避免 Lua 运行错误发生在部分写入之后。
redis.replicate_commands()
local expected = {'string', 'string', 'hash', 'string', 'string'}
for i = 1, 5 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= expected[i] then return -2 end
end
if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
local count = redis.call('GET', KEYS[4])
if count and count ~= '0' and not string.match(count, '^[1-9]%d*$') then return -2 end
if tonumber(count or '0') >= tonumber(ARGV[5]) then return -1 end
local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local old = redis.call('GET', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])
if old and ttl > 0 then
    redis.call('HSET', KEYS[3], 'value', old, 'expires', now + ttl)
    local owner = redis.call('GET', KEYS[5])
    if owner then redis.call('HSET', KEYS[3], 'owner', owner) end
else
    redis.call('HSET', KEYS[3], 'expires', 0)
end
redis.call('PEXPIRE', KEYS[3], ARGV[4])
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3])
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[4])
redis.call('SET', KEYS[5], ARGV[1], 'PX', ARGV[4])
local attempts = redis.call('INCR', KEYS[4])
if attempts == 1 then redis.call('PEXPIRE', KEYS[4], 86400000) end
return 1
