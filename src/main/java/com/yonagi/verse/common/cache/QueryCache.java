package com.yonagi.verse.common.cache;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 统一缓存：代际隔离、写栅栏、看门狗互斥重建及有界回源。 */
@Slf4j
@Component
public class QueryCache {
    private static final DefaultRedisScript<List> SNAPSHOT = new DefaultRedisScript<>("""
            local result = {}
            for i=1,#KEYS,2 do
                if redis.call('SCARD',KEYS[i+1]) > 0 then return {'BUSY'} end
                result[#result+1] = redis.call('GET',KEYS[i]) or '0'
            end
            return result
            """, List.class);
    private static final DefaultRedisScript<Long> BEGIN = new DefaultRedisScript<>("""
            if redis.replicate_commands then redis.replicate_commands() end
            redis.call('SET',KEYS[1],ARGV[1])
            if redis.call('SADD',KEYS[2],ARGV[2]) == 1 then
                local time = redis.call('TIME')
                redis.call('ZADD',KEYS[3],time[1]*1000+math.floor(time[2]/1000),ARGV[2])
                redis.call('HSET',KEYS[4],ARGV[2],ARGV[3])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> COMPLETED = new DefaultRedisScript<>("""
            if redis.replicate_commands then redis.replicate_commands() end
            if redis.call('SISMEMBER',KEYS[1],ARGV[1]) == 1 then
                local time = redis.call('TIME')
                redis.call('ZADD',KEYS[2],'NX',time[1]*1000+math.floor(time[2]/1000),ARGV[1])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER',KEYS[2],ARGV[2]) == 1 then
                if not redis.call('ZSCORE',KEYS[5],ARGV[2]) then return 0 end
                redis.call('SET',KEYS[1],ARGV[1])
                redis.call('SREM',KEYS[2],ARGV[2])
            end
            redis.call('ZREM',KEYS[3],ARGV[2])
            redis.call('HDEL',KEYS[4],ARGV[2])
            redis.call('ZREM',KEYS[5],ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<List> FENCE_STATS = new DefaultRedisScript<>("""
            local count = redis.call('SCARD',KEYS[1])
            local oldest = redis.call('ZRANGE',KEYS[2],0,0,'WITHSCORES')
            local time = redis.call('TIME')
            local age = 0
            if #oldest > 0 then age = math.max(0,time[1]*1000+math.floor(time[2]/1000)-tonumber(oldest[2])) end
            return {count, tostring(age), redis.call('ZCARD',KEYS[3]), math.max(0,count-redis.call('ZCARD',KEYS[2]))}
            """, List.class);
    private static final String INSTANCE = ProcessHandle.current().pid() + ":" + UUID.randomUUID();
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            if redis.replicate_commands then redis.replicate_commands() end
            for i=2,#KEYS,3 do
                local n = (i-2)/3+1
                if redis.call('SCARD',KEYS[i+1]) > 0
                    or (redis.call('GET',KEYS[i]) or '0') ~= ARGV[n+2] then return 0 end
            end
            local time = redis.call('TIME')
            local now = time[1]*1000+math.floor(time[2]/1000)
            for i=2,#KEYS,3 do
                redis.call('ZREMRANGEBYSCORE',KEYS[i+2],'-inf',now)
                redis.call('ZADD',KEYS[i+2],now+tonumber(ARGV[2]),KEYS[1])
                if redis.call('PTTL',KEYS[i+2]) < tonumber(ARGV[2])+1000 then
                    redis.call('PEXPIRE',KEYS[i+2],tonumber(ARGV[2])+1000)
                end
            end
            redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final QueryCacheProperties properties;
    private final MeterRegistry metrics;
    private final Semaphore permits;
    private final String testScope;
    private final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> false);

    @org.springframework.beans.factory.annotation.Autowired
    public QueryCache(StringRedisTemplate redis, RedissonClient redisson, QueryCacheProperties properties,
                      MeterRegistry metrics) {
        this(redis, redisson, properties, metrics, "");
    }

    /** 仅供同包集成测试追加隔离范围；生产键前缀统一取自 RedisKeyConstant。 */
    QueryCache(StringRedisTemplate redis, RedissonClient redisson, QueryCacheProperties properties,
               MeterRegistry metrics, String testScope) {
        this.redis = redis; this.redisson = redisson; this.properties = properties; this.metrics = metrics;
        this.permits = new Semaphore(properties.getMaxConcurrency(), true);
        this.testScope = testScope;
    }

    @FunctionalInterface
    public interface Loader { Object load() throws Throwable; }

    /** 安全状态不信任缓存；实时复核也共用回源限额，故障时拒绝无限回退数据库。 */
    public <T> T check(java.util.function.Supplier<T> check) {
        boolean ownsPermit = !loading.get();
        if (ownsPermit && !permits.tryAcquire()) {
            throw busy();
        }
        try { return check.get(); }
        finally {
            if (ownsPermit) {
                permits.release();
            }
        }
    }

    public <T> T read(String name, String keyPrefix, Object parameters, Class<T> type,
                      List<String> tables, long ttlMillis, java.util.function.Supplier<T> loader) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }
        return readResult(type, () -> get(name, keyPrefix, parameters, type, tables, ttlMillis,
                () -> { }, loader::get, value -> true));
    }

    public Object get(String name, String keyPrefix, Object parameters, Type type, List<String> tables, long ttlMillis,
                      Runnable accessCheck, Loader loader, Predicate<Object> cacheable) throws Throwable {
        return get(name, keyPrefix, parameters, type, tables, ttlMillis,
                accessCheck, loader, cacheable, error -> false);
    }

    public Object get(String name, String keyPrefix, Object parameters, Type type, List<String> tables, long ttlMillis,
                      Runnable accessCheck, Loader loader, Predicate<Object> cacheable,
                      Predicate<ClientException> cacheFailure) throws Throwable {
        return getDependencies(name, keyPrefix, parameters, type,
                tables.stream().map(QueryDependency::table).toList(), ttlMillis,
                accessCheck, loader, cacheable, cacheFailure);
    }

    /** 显式范围入口；事务内仍直接读取权威数据，原手工 read 不受影响。 */
    public <T> T readScoped(String name, String keyPrefix, Object parameters, Class<T> type,
                            List<QueryDependency> dependencies, long ttlMillis,
                            java.util.function.Supplier<T> loader) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }
        return readResult(type, () -> getScoped(name, keyPrefix, parameters, type, dependencies, ttlMillis,
                () -> { }, loader::get, value -> true, error -> false));
    }

    /** A-06：仅在 Supplier 与 AOP Throwable 契约的最外层桥接，致命 Error 原样传播。 */
    private <T> T readResult(Class<T> type, Loader loader) {
        try {
            return type.cast(loader.load());
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new ServerException("查询缓存构建失败", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    /** 按完整依赖读取；非法声明必须在 Redis 降级边界之外拒绝。 */
    public Object getScoped(String name, String keyPrefix, Object parameters, Type type,
                            List<QueryDependency> dependencies, long ttlMillis,
                            Runnable accessCheck, Loader loader, Predicate<Object> cacheable,
                            Predicate<ClientException> cacheFailure) throws Throwable {
        if (keyPrefix == null || !keyPrefix.startsWith("verse:") || keyPrefix.startsWith("verse:query-cache:")
                || keyPrefix.startsWith("verse:lock_") || ttlMillis <= 0) {
            throw new ServerException("查询缓存作用域前缀或有效期无效");
        }
        return getDependencies(name, keyPrefix, parameters, type, readDependencies(dependencies), ttlMillis,
                accessCheck, loader, cacheable, cacheFailure);
    }

    private List<QueryDependency> readDependencies(List<QueryDependency> dependencies) {
        try {
            if (dependencies == null || dependencies.isEmpty()) {
                throw new IllegalArgumentException("查询缓存依赖不能为空");
            }
            List<String> tables = dependencies.stream().map(QueryDependency::table).distinct().sorted().toList();
            List<QueryDependency> normalized = QueryCacheKey.dependencies(
                    dependencies, tables, properties.getMaxDependencies());
            boolean preparing = false;
            Map<String, QueryDependency> expanded = new TreeMap<>();
            for (QueryDependency dependency : normalized) {
                expanded.put(dependency.identity(), dependency);
                if (!dependency.isTable()) {
                    QueryCacheProperties.TableMode mode = properties.tableMode(dependency.table());
                    if (mode == QueryCacheProperties.TableMode.LEGACY) {
                        throw new IllegalArgumentException("查询缓存作用域表尚未准备");
                    }
                    preparing |= mode == QueryCacheProperties.TableMode.PREPARE;
                    QueryDependency broadcast = QueryDependency.broadcast(dependency.table());
                    expanded.put(broadcast.identity(), broadcast);
                }
            }
            if (expanded.size() > properties.getMaxDependencies()) {
                throw new IllegalArgumentException("查询缓存有效依赖超过上限");
            }
            return preparing ? tables.stream().map(QueryDependency::table).toList()
                    : List.copyOf(expanded.values());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ServerException("查询缓存依赖配置无效", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private Object getDependencies(String name, String keyPrefix, Object parameters, Type type,
                                   List<QueryDependency> dependencies, long ttlMillis, Runnable accessCheck,
                                   Loader loader, Predicate<Object> cacheable,
                                   Predicate<ClientException> cacheFailure) throws Throwable {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getWaitMillis());
        boolean scopedRead = dependencies.stream().anyMatch(dependency -> !dependency.isTable());
        Object keyParameters = scopedRead ? Arrays.asList(parameters,
                dependencies.stream().map(QueryDependency::identity).toList()) : parameters;
        String digest = DigestUtil.sha256Hex(QueryCacheKey.parametersJson(keyParameters));
        // 业务前缀与参数、代际摘要分离；服务方法名只作为指标标签，不进入 Redis 键。
        String prefix = scoped(keyPrefix) + (scopedRead ? "scope-v2:" : "");
        try {
            while (System.nanoTime() < deadline) {
                List<String> versions = snapshotDependencies(dependencies);
                if (versions == null) { pause(); continue; }
                String key = prefix + digest + ":" + DigestUtil.sha256Hex(JSON.toJSONString(versions));
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    if (!versions.equals(snapshotDependencies(dependencies))) {
                        continue;
                    }
                    guarded(accessCheck);
                    if (!versions.equals(snapshotDependencies(dependencies))) {
                        continue;
                    }
                    Object value = decode(key, cached, type);
                    if (value != CORRUPT) {
                        if (!versions.equals(snapshotDependencies(dependencies))) {
                            continue;
                        }
                        count(name, "hit");
                        return value;
                    }
                }
                var lock = redisson.getLock(scoped(RedisKeyConstant.CORE_QUERY_CACHE_LOCK_KEY)
                        + DigestUtil.sha256Hex(prefix) + ":" + digest);
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                // 不指定 leaseTime，使用 Redisson 看门狗，慢查询不会因固定租约到期产生第二个构建者。
                long lockStarted = System.nanoTime();
                boolean acquired = false;
                // 指标记录也可能失败，成功拿锁后的所有出口必须进入释放边界。
                try {
                    try {
                        acquired = lock.tryLock(remaining, TimeUnit.MILLISECONDS);
                    } finally {
                        metrics.timer("verse.query.cache.lock.wait", "query", name)
                                .record(System.nanoTime() - lockStarted, TimeUnit.NANOSECONDS);
                    }
                    if (!acquired) {
                        throw busy();
                    }
                    versions = snapshotDependencies(dependencies);
                    if (versions == null) {
                        continue;
                    }
                    key = prefix + digest + ":" + DigestUtil.sha256Hex(JSON.toJSONString(versions));
                    cached = redis.opsForValue().get(key);
                    if (cached != null) {
                        if (!versions.equals(snapshotDependencies(dependencies))) {
                            continue;
                        }
                        guarded(accessCheck);
                        if (!versions.equals(snapshotDependencies(dependencies))) {
                            continue;
                        }
                        Object value = decode(key, cached, type);
                        if (value != CORRUPT && versions.equals(snapshotDependencies(dependencies))) {
                            count(name, "hit"); return value;
                        }
                    }
                    boolean ownsPermit = !loading.get();
                    if (ownsPermit && !permits.tryAcquire()) { count(name, "rejected"); throw busy(); }
                    loading.set(true);
                    try {
                        count(name, "miss");
                        Object value;
                        JSONObject envelope = new JSONObject();
                        checkAccess(accessCheck);
                        try { value = loadData(name, loader); }
                        catch (ClientException error) {
                            if (cacheFailure.test(error)) {
                                envelope.put("errorCode", error.getErrorCode());
                                envelope.put("errorMessage", error.getErrorMessage());
                                publishDependencies(key, dependencies, versions, envelope, negativeTtl());
                            }
                            throw error;
                        }
                        envelope.put("value", value);
                        boolean absent = value == null || value instanceof Collection<?> c && c.isEmpty();
                        if (cacheable.test(value)) {
                            publishDependencies(key, dependencies, versions, envelope,
                                    absent ? negativeTtl() : jitter(ttlMillis));
                        }
                        // 写入发生时不返回这个旧构建结果，重试新代际或有界失败。
                        if (!versions.equals(snapshotDependencies(dependencies))) {
                            continue;
                        }
                        return value;
                    } finally { if (ownsPermit) { loading.remove(); permits.release(); } }
                } finally {
                    if (acquired && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
            count(name, "timeout"); throw busy();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw busy();
        } catch (org.springframework.dao.DataAccessException | org.redisson.client.RedisException error) {
            count(name, "unavailable");
            // 只有缓存基础设施异常到达这里；业务 SQL 异常在调用边界保留原因，不能冒充 Redis 故障。
            log.warn("[query-cache] 读缓存异常，使用有界数据库回源: query={}", name, error);
            boolean ownsPermit = !loading.get();
            if (ownsPermit && !permits.tryAcquire()) { count(name, "rejected"); throw busy(); }
            loading.set(true);
            try {
                checkAccess(accessCheck);
                Object value = loadData(name, loader);
                count(name, "fallback");
                return value;
            } finally { if (ownsPermit) { loading.remove(); permits.release(); } }
        }
    }

    /** 业务读取失败与 Redis 读写失败分开，向异常处理器保留底层 SQL/连接错误。 */
    private Object loadData(String name, Loader loader) throws Throwable {
        long started = System.nanoTime();
        try { return loader.load(); }
        catch (org.springframework.dao.DataAccessException error) {
            throw new ServerException("数据查询失败，请稍后重试", error, BaseErrorCode.SERVICE_ERROR);
        } finally {
            metrics.timer("verse.query.cache.load", "query", name)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void checkAccess(Runnable check) {
        try { check.run(); }
        catch (org.springframework.dao.DataAccessException error) {
            throw new ServerException("权限数据查询失败，请稍后重试", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private static final Object CORRUPT = new Object();
    private Object decode(String key, String json, Type type) {
        JSONObject envelope;
        try { envelope = JSON.parseObject(json); }
        catch (RuntimeException error) { redis.delete(key); return CORRUPT; }
        if (envelope == null || !envelope.containsKey("value") && !envelope.containsKey("errorCode")) {
            redis.delete(key); return CORRUPT;
        }
        if (envelope.containsKey("errorCode")) {
            String code = envelope.getString("errorCode"), message = envelope.getString("errorMessage");
            throw new ClientException(new IErrorCode() {
                public String code() { return code; }
                public String message() { return message; }
            });
        }
        try { return JSON.parseObject(JSON.toJSONString(envelope.get("value")), type); }
        catch (RuntimeException error) { redis.delete(key); return CORRUPT; }
    }

    private void guarded(Runnable check) {
        if (loading.get()) { checkAccess(check); return; }
        if (!permits.tryAcquire()) {
            throw busy();
        }
        try { checkAccess(check); } finally { permits.release(); }
    }

    @SuppressWarnings("unchecked")
    List<String> snapshot(List<String> tables) {
        return snapshotDependencies(tables.stream().map(QueryDependency::table).toList());
    }

    private List<String> snapshotDependencies(List<QueryDependency> dependencies) {
        List<String> keys = new ArrayList<>();
        dependencies.forEach(dependency -> {
            keys.add(controlKey(dependency, "version"));
            keys.add(controlKey(dependency, "writers"));
        });
        List<?> values = redis.execute(SNAPSHOT, keys);
        if (values == null) {
            throw busy();
        }
        if (values.size() == 1 && "BUSY".equals(values.getFirst())) {
            return null;
        }
        return values.stream().map(String::valueOf).toList();
    }

    void publish(String key, List<String> tables, List<String> versions, JSONObject value, long ttl) {
        publishDependencies(key, tables.stream().map(QueryDependency::table).toList(), versions, value, ttl);
    }

    private void publishDependencies(String key, List<QueryDependency> dependencies,
                                     List<String> versions, JSONObject value, long ttl) {
        String json = JSON.toJSONString(value, JSONWriter.Feature.WriteNulls);
        // 大响应仍覆盖缓存，通过指标提示容量评估，不能悄悄变成每次回源。
        if (json.getBytes(StandardCharsets.UTF_8).length > properties.getLargeValueBytes()) {
            count("payload", "large");
        }
        List<String> keys = new ArrayList<>(List.of(key));
        dependencies.forEach(dependency -> {
            keys.add(controlKey(dependency, "version"));
            keys.add(controlKey(dependency, "writers"));
            keys.add(controlKey(dependency, "index"));
        });
        List<String> arguments = new ArrayList<>(List.of(json, Long.toString(ttl)));
        arguments.addAll(versions);
        redis.execute(PUBLISH, keys, arguments.toArray());
    }

    /** 先栅栏和换代，再物理删除；任何 Redis 失败必须阻止后续 SQL。 */
    public void beforeWrite(String table, String token) {
        require(redis.execute(BEGIN, List.of(versionKey(table), writerKey(table),
                        writerCreatedKey(table), writerOwnerKey(table)),
                UUID.randomUUID().toString(), token, INSTANCE));
        deleteIndexed(table);
    }

    /** SQL 前保护完整范围；旧 TABLE 委托原入口以保留既有契约。 */
    public void beforeWrite(QueryDependency dependency, String token) {
        if (dependency.isTable()) {
            beforeWrite(dependency.table(), token);
            return;
        }
        require(redis.execute(QueryScopedScripts.BEGIN, controlKeys(dependency),
                UUID.randomUUID().toString(), token, INSTANCE, dependency.identity()));
        metrics.counter("verse.query.cache.scope.prepare", "table", dependency.table(),
                "scopeKind", dependency.scope().kind().name()).increment();
        deleteIndexed(dependency);
    }

    /** 调用方必须已确认 SQL/事务结束；先持久保存证明，再清理，进程退出后仍能重试。 */
    public boolean afterWrite(String table, String token) {
        require(redis.execute(COMPLETED, List.of(writerKey(table), writerCompletedKey(table)), token));
        return cleanupCompleted(table, token);
    }

    /** 仅在原事务或会话已确认结束后记录完成；动态登记供跨进程重试。 */
    public boolean afterWrite(QueryDependency dependency, String token) {
        if (dependency.isTable()) {
            return afterWrite(dependency.table(), token);
        }
        require(redis.execute(COMPLETED, List.of(controlKey(dependency, "writers"),
                controlKey(dependency, "writer-completed")), token));
        return cleanupCompleted(dependency, token);
    }

    boolean cleanupCompleted(QueryDependency dependency, String token) {
        if (dependency.isTable()) {
            return cleanupCompleted(dependency.table(), token);
        }
        if (redis.opsForZSet().score(controlKey(dependency, "writer-completed"), token) == null) {
            boolean absent = !Boolean.TRUE.equals(redis.opsForSet()
                    .isMember(controlKey(dependency, "writers"), token));
            removeEmptyRegistration(dependency);
            return absent;
        }
        if (!deleteIndexed(dependency)) {
            return false;
        }
        require(redis.execute(QueryScopedScripts.FINISH, controlKeys(dependency),
                UUID.randomUUID().toString(), token, dependency.identity()));
        return true;
    }

    void removeEmptyRegistration(QueryDependency dependency) {
        redis.execute(QueryScopedScripts.REMOVE_EMPTY_REGISTRATION, controlKeys(dependency),
                "", "", dependency.identity());
    }

    Set<String> activeDependencies(int limit) {
        Set<String> resources = redis.opsForZSet().range(activeDependenciesKey(), 0, limit - 1L);
        if (resources == null) {
            throw busy();
        }
        return resources;
    }

    void rotateDependency(String identity) {
        require(redis.execute(QueryScopedScripts.ROTATE, List.of(activeDependenciesKey()), identity));
    }

    long activeDependencyCount() {
        Long count = redis.opsForZSet().size(activeDependenciesKey());
        if (count == null) {
            throw busy();
        }
        return count;
    }

    /** 恢复任务只读取已结束集合，不把创建时间、实例失联或年龄当作结束证明。 */
    boolean cleanupCompleted(String table, String token) {
        if (redis.opsForZSet().score(writerCompletedKey(table), token) == null) {
            return !Boolean.TRUE.equals(redis.opsForSet().isMember(writerKey(table), token));
        }
        if (!deleteIndexed(table)) {
            return false;
        }
        require(redis.execute(FINISH, List.of(versionKey(table), writerKey(table), writerCreatedKey(table),
                writerOwnerKey(table), writerCompletedKey(table)), UUID.randomUUID().toString(), token));
        return true;
    }

    Set<String> completedWriters(String table, int limit) {
        Set<String> tokens = redis.opsForZSet().range(writerCompletedKey(table), 0, limit - 1L);
        if (tokens == null) {
            throw busy();
        }
        return tokens;
    }

    Set<String> completedWriters(QueryDependency dependency, int limit) {
        Set<String> tokens = redis.opsForZSet().range(controlKey(dependency, "writer-completed"), 0, limit - 1L);
        if (tokens == null) {
            throw busy();
        }
        return tokens;
    }

    long[] fenceStats(String table) {
        List<?> values = redis.execute(FENCE_STATS, List.of(writerKey(table),
                writerCreatedKey(table), writerCompletedKey(table)));
        if (values == null || values.size() != 4) {
            throw busy();
        }
        return values.stream().mapToLong(value -> Long.parseLong(String.valueOf(value))).toArray();
    }

    long[] fenceStats(QueryDependency dependency) {
        List<?> values = redis.execute(FENCE_STATS, List.of(controlKey(dependency, "writers"),
                controlKey(dependency, "writer-created"), controlKey(dependency, "writer-completed")));
        if (values == null || values.size() != 4) {
            throw busy();
        }
        return values.stream().mapToLong(value -> Long.parseLong(String.valueOf(value))).toArray();
    }

    private boolean deleteIndexed(String table) {
        return deleteIndexed(QueryDependency.table(table));
    }

    private boolean deleteIndexed(QueryDependency dependency) {
        // 换代和栅栏已阻止旧结果命中及发布；大索引分多轮清理，避免阻塞事务完成线程。
        for (int i = 0; i < properties.getFenceCleanupBatches(); i++) {
            Set<String> keys = redis.opsForZSet().range(controlKey(dependency, "index"), 0, 199);
            if (keys == null) {
                throw busy();
            }
            if (keys.isEmpty()) {
                return true;
            }
            List<String> batch = new ArrayList<>(keys);
            if (!dependency.isTable() && batch.stream().anyMatch(key -> !key.startsWith("verse:")
                    || key.startsWith("verse:query-cache:") || key.startsWith("verse:lock_")
                    || !key.contains("scope-v2:"))) {
                throw new ServerException("作用域缓存索引包含非法结果键，保留栅栏");
            }
            redis.delete(batch);
            redis.opsForZSet().remove(controlKey(dependency, "index"), batch.toArray());
        }
        Long remaining = redis.opsForZSet().size(controlKey(dependency, "index"));
        if (remaining == null) {
            throw busy();
        }
        return remaining == 0;
    }

    private void require(Long result) {
        if (!Long.valueOf(1).equals(result)) {
            throw busy();
        }
    }
    private long negativeTtl() { return jitter(properties.getNegativeSeconds()*1000L); }
    // 向上抖动，业务基础 TTL 不被缩短；价格与邀请码等时间语义在读取时计算。
    long jitter(long ttl) { return Math.max(1, ttl+ThreadLocalRandom.current().nextLong(Math.max(1,ttl/5+1))); }
    private void pause() throws InterruptedException { Thread.sleep(20); }
    private void count(String name, String outcome) {
        metrics.counter("verse.query.cache", "query", name, "outcome", outcome).increment();
    }
    static ServerException busy() { return new ServerException("查询缓存繁忙，请稍后重试"); }
    private String scoped(String keyPrefix) {
        return keyPrefix + testScope;
    }
    String versionKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_VERSION_KEY)+table; }
    String writerKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_WRITERS_KEY)+table; }
    String writerCreatedKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_WRITER_CREATED_KEY)+table; }
    String writerOwnerKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_WRITER_OWNER_KEY)+table; }
    String writerCompletedKey(String table) {
        return scoped(RedisKeyConstant.CORE_QUERY_CACHE_WRITER_COMPLETED_KEY) + table;
    }
    String indexKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_INDEX_KEY)+table; }

    String activeDependenciesKey() {
        return scoped(RedisKeyConstant.QUERY_CACHE_ACTIVE_DEPENDENCIES_KEY);
    }

    String controlKey(QueryDependency dependency, String type) {
        if (!dependency.isTable()) {
            return scoped(RedisKeyConstant.QUERY_CACHE_V2_KEY + type + ":") + dependency.identity();
        }
        return switch (type) {
            case "version" -> versionKey(dependency.table());
            case "writers" -> writerKey(dependency.table());
            case "writer-created" -> writerCreatedKey(dependency.table());
            case "writer-owner" -> writerOwnerKey(dependency.table());
            case "writer-completed" -> writerCompletedKey(dependency.table());
            case "index" -> indexKey(dependency.table());
            default -> throw new IllegalArgumentException("查询缓存控制键类型无效");
        };
    }

    private List<String> controlKeys(QueryDependency dependency) {
        return List.of(controlKey(dependency, "version"), controlKey(dependency, "writers"),
                controlKey(dependency, "writer-created"), controlKey(dependency, "writer-owner"),
                controlKey(dependency, "writer-completed"), controlKey(dependency, "index"), activeDependenciesKey());
    }
}
