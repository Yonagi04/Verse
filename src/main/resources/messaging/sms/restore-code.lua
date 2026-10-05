-- UUID 所有权和验证码值都必须匹配，不能恢复被消费或被新请求替换的状态。
redis.replicate_commands()
local expected = {'string', 'string', 'hash', 'string'}
for i = 1, 4 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= expected[i] then return -2 end
end
if redis.call('GET', KEYS[4]) ~= ARGV[1] then
    redis.call('DEL', KEYS[3])
    return 0
end
if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end
if redis.call('GET', KEYS[1]) ~= ARGV[2] then
    redis.call('DEL', KEYS[3], KEYS[4])
    return 0
end
local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local expires = tonumber(redis.call('HGET', KEYS[3], 'expires') or '0')
local old = redis.call('HGET', KEYS[3], 'value')
if old and expires > now then
    redis.call('SET', KEYS[1], old, 'PX', expires - now)
    local owner = redis.call('HGET', KEYS[3], 'owner')
    if owner then redis.call('SET', KEYS[4], owner, 'PX', expires - now)
    else redis.call('DEL', KEYS[4]) end
else
    redis.call('DEL', KEYS[1], KEYS[4])
end
redis.call('DEL', KEYS[3])
return 1
