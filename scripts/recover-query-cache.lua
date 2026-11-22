-- 停止全部写入者并确认数据库事务结束后使用；年龄和实例失联不构成证明。
-- KEYS: 原六键；v2 额外传入固定活动资源注册表，六个控制键须属于同一资源。
-- ARGV: dry-run/apply, 核验声明, dry-run 代际, 新 UUID 代际。
if #KEYS ~= 6 and #KEYS ~= 7 then return redis.error_reply('six legacy or seven v2 keys required') end
local v2 = #KEYS == 7
if v2 and KEYS[7] ~= 'verse:query-cache:v2:active-dependencies' then
    return redis.error_reply('invalid active dependency registry')
end
local prefixes = {'version:', 'writers:', 'writer-created:', 'writer-owner:', 'writer-completed:', 'index:'}
local types = {'string', 'set', 'zset', 'hash', 'zset', 'zset', 'zset'}
local scope = nil
local function valid_id(value)
    return value and string.match(value, '^[1-9][0-9]*$')
        and (#value < 19 or (#value == 19 and value <= '9223372036854775807'))
end
local function valid_resource(value)
    if string.match(value, '^b:t_[a-z0-9_]+$') then return true end
    local table_name, tenant, user = string.match(value, '^s:(t_[a-z0-9_]+):tenant:([0-9]+):user:([0-9]+)$')
    if table_name then return valid_id(tenant) and valid_id(user) end
    local kind, id
    table_name, kind, id = string.match(value, '^s:(t_[a-z0-9_]+):([a-z]+):([0-9]+)$')
    return table_name and (kind == 'user' or kind == 'tenant') and valid_id(id)
end
for i = 1, 6 do
    local prefix = (v2 and 'verse:query-cache:v2:' or 'verse:query-cache:') .. prefixes[i]
    if string.sub(KEYS[i], 1, #prefix) ~= prefix then return redis.error_reply('invalid cache key') end
    local suffix = string.sub(KEYS[i], #prefix + 1)
    if (v2 and not valid_resource(suffix)) or (not v2 and not string.match(suffix, 't_[a-z0-9_]+$')) then
        return redis.error_reply('invalid dependency scope')
    end
    if scope and scope ~= suffix then return redis.error_reply('different table scopes') end
    scope = suffix
end
for i = 1, #KEYS do
    local actual = redis.call('TYPE', KEYS[i]).ok
    if actual ~= 'none' and actual ~= types[i] then return redis.error_reply('invalid control type') end
end
local version = redis.call('GET', KEYS[1]) or '0'
if ARGV[1] == nil or ARGV[1] == 'dry-run' then
    local tokens = redis.call('SSCAN', KEYS[2], '0', 'COUNT', 32)
    while #tokens[2] > 32 do table.remove(tokens[2]) end
    return {'DRY_RUN', version, redis.call('SCARD', KEYS[2]), redis.call('ZCARD', KEYS[6]), tokens}
end
if ARGV[1] ~= 'apply' or ARGV[2] ~= 'WRITERS_STOPPED_AND_DB_TRANSACTIONS_ENDED' then
    return redis.error_reply('maintenance verification required')
end
if ARGV[3] ~= version then return redis.error_reply('generation changed; repeat verification') end
if not ARGV[4] or #ARGV[4] ~= 36 or ARGV[4] == version then return redis.error_reply('new UUID generation required') end
local values = redis.call('ZRANGE', KEYS[6], 0, 199)
if #values > 0 then
    -- 索引来自缓存发布；拒绝损坏索引中的外部键，避免删除无关数据。
    for _, key in ipairs(values) do
        if string.sub(key, 1, 6) ~= 'verse:' or string.match(key, '^verse:query%-cache:') or string.match(key, '^verse:lock_') then
            return redis.error_reply('invalid indexed key')
        end
        if v2 and not string.find(key, 'scope-v2:', 1, true) then
            return redis.error_reply('invalid v2 result key')
        end
    end
    redis.call('DEL', unpack(values))
    redis.call('ZREM', KEYS[6], unpack(values))
end
local remaining = redis.call('ZCARD', KEYS[6])
if remaining > 0 then return {'PENDING', version, remaining} end
-- 完整失效并换代后才释放；清理中途退出仍然保留栅栏。
redis.call('SET', KEYS[1], ARGV[4])
redis.call('DEL', KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6])
if v2 then redis.call('ZREM', KEYS[7], scope) end
return {'RECOVERED', ARGV[4], 0}
