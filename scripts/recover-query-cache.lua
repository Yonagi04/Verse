-- 停止全部写入者并确认数据库事务结束后使用；年龄和实例失联不构成证明。
-- KEYS: version, writers, writer-created, writer-owner, writer-completed, index。
-- ARGV: dry-run/apply, 核验声明, dry-run 代际, 新 UUID 代际。
if #KEYS ~= 6 then return redis.error_reply('six table-scoped keys required') end
local prefixes = {'version:', 'writers:', 'writer-created:', 'writer-owner:', 'writer-completed:', 'index:'}
local scope = nil
for i = 1, 6 do
    local prefix = 'verse:query-cache:' .. prefixes[i]
    if string.sub(KEYS[i], 1, #prefix) ~= prefix then return redis.error_reply('invalid cache key') end
    local suffix = string.sub(KEYS[i], #prefix + 1)
    if not string.match(suffix, 't_[a-z0-9_]+$') then return redis.error_reply('invalid table scope') end
    if scope and scope ~= suffix then return redis.error_reply('different table scopes') end
    scope = suffix
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
    end
    redis.call('DEL', unpack(values))
    redis.call('ZREM', KEYS[6], unpack(values))
end
local remaining = redis.call('ZCARD', KEYS[6])
if remaining > 0 then return {'PENDING', version, remaining} end
-- 完整失效并换代后才释放；清理中途退出仍然保留栅栏。
redis.call('SET', KEYS[1], ARGV[4])
redis.call('DEL', KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6])
return {'RECOVERED', ARGV[4], 0}
