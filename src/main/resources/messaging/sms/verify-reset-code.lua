-- Lua 不自动回滚：先校验类型及参数，再写凭证，最后消费验证码和发送所有权。
for i = 1, 3 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= 'string' then return -1 end
end
if not ARGV[1] or ARGV[1] == '' or not ARGV[2] or string.len(ARGV[2]) ~= 32 then return -1 end
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('PTTL', KEYS[1]) <= 0 then return 0 end
redis.call('SET', KEYS[3], ARGV[2], 'PX', 600000)
redis.call('DEL', KEYS[1], KEYS[2])
return 1
