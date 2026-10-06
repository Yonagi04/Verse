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
import java.util.*;
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
            redis.call('SET',KEYS[1],ARGV[1])
            redis.call('SADD',KEYS[2],ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
            redis.call('SET',KEYS[1],ARGV[1])
            redis.call('SREM',KEYS[2],ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            if redis.replicate_commands then redis.replicate_commands() end
            for i=2,#KEYS,3 do
                local n = (i-2)/3+1
                if redis.call('SCARD',KEYS[i+1]) > 0 or (redis.call('GET',KEYS[i]) or '0') ~= ARGV[n+2] then return 0 end
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
        if (ownsPermit && !permits.tryAcquire()) throw busy();
        try { return check.get(); }
        finally { if (ownsPermit) permits.release(); }
    }

    public <T> T read(String name, String keyPrefix, Object parameters, Class<T> type, List<String> tables, long ttlMillis,
                      java.util.function.Supplier<T> loader) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        try { return type.cast(get(name, keyPrefix, parameters, type, tables, ttlMillis, () -> { }, loader::get, value -> true)); }
        catch (RuntimeException | Error error) { throw error; }
        catch (Throwable error) { throw new ServerException("查询缓存构建失败", error, BaseErrorCode.SERVICE_ERROR); }
    }

    public Object get(String name, String keyPrefix, Object parameters, Type type, List<String> tables, long ttlMillis,
                      Runnable accessCheck, Loader loader, Predicate<Object> cacheable) throws Throwable {
        return get(name, keyPrefix, parameters, type, tables, ttlMillis, accessCheck, loader, cacheable, error -> false);
    }

    public Object get(String name, String keyPrefix, Object parameters, Type type, List<String> tables, long ttlMillis,
                      Runnable accessCheck, Loader loader, Predicate<Object> cacheable,
                      Predicate<ClientException> cacheFailure) throws Throwable {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getWaitMillis());
        String digest = DigestUtil.sha256Hex(QueryCacheKey.parametersJson(parameters));
        // 业务前缀与参数、代际摘要分离；服务方法名只作为指标标签，不进入 Redis 键。
        String prefix = scoped(keyPrefix);
        try {
            while (System.nanoTime() < deadline) {
                List<String> versions = snapshot(tables);
                if (versions == null) { pause(); continue; }
                String key = prefix + digest + ":" + DigestUtil.sha256Hex(JSON.toJSONString(versions));
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    if (!versions.equals(snapshot(tables))) continue;
                    guarded(accessCheck);
                    if (!versions.equals(snapshot(tables))) continue;
                    Object value = decode(key, cached, type);
                    if (value != CORRUPT) {
                        if (!versions.equals(snapshot(tables))) continue;
                        count(name, "hit");
                        return value;
                    }
                }
                var lock = redisson.getLock(scoped(RedisKeyConstant.CORE_QUERY_CACHE_LOCK_KEY)
                        + DigestUtil.sha256Hex(prefix) + ":" + digest);
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                // 不指定 leaseTime，使用 Redisson 看门狗，慢查询不会因固定租约到期产生第二个构建者。
                if (!lock.tryLock(remaining, TimeUnit.MILLISECONDS)) throw busy();
                try {
                    versions = snapshot(tables);
                    if (versions == null) continue;
                    key = prefix + digest + ":" + DigestUtil.sha256Hex(JSON.toJSONString(versions));
                    cached = redis.opsForValue().get(key);
                    if (cached != null) {
                        if (!versions.equals(snapshot(tables))) continue;
                        guarded(accessCheck);
                        if (!versions.equals(snapshot(tables))) continue;
                        Object value = decode(key, cached, type);
                        if (value != CORRUPT && versions.equals(snapshot(tables))) {
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
                        try { value = loadData(loader); }
                        catch (ClientException error) {
                            if (cacheFailure.test(error)) {
                                envelope.put("errorCode", error.getErrorCode());
                                envelope.put("errorMessage", error.getErrorMessage());
                                publish(key, tables, versions, envelope, negativeTtl());
                            }
                            throw error;
                        }
                        envelope.put("value", value);
                        boolean absent = value == null || value instanceof Collection<?> c && c.isEmpty();
                        if (cacheable.test(value)) publish(key, tables, versions, envelope,
                                absent ? negativeTtl() : jitter(ttlMillis));
                        // 写入发生时不返回这个旧构建结果，重试新代际或有界失败。
                        if (!versions.equals(snapshot(tables))) continue;
                        return value;
                    } finally { if (ownsPermit) { loading.remove(); permits.release(); } }
                } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
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
                Object value = loadData(loader);
                count(name, "fallback");
                return value;
            } finally { if (ownsPermit) { loading.remove(); permits.release(); } }
        }
    }

    /** 业务读取失败与 Redis 读写失败分开，向异常处理器保留底层 SQL/连接错误。 */
    private Object loadData(Loader loader) throws Throwable {
        try { return loader.load(); }
        catch (org.springframework.dao.DataAccessException error) {
            throw new ServerException("数据查询失败，请稍后重试", error, BaseErrorCode.SERVICE_ERROR);
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
        if (!permits.tryAcquire()) throw busy();
        try { checkAccess(check); } finally { permits.release(); }
    }

    @SuppressWarnings("unchecked")
    List<String> snapshot(List<String> tables) {
        List<String> keys = new ArrayList<>();
        tables.forEach(t -> { keys.add(versionKey(t)); keys.add(writerKey(t)); });
        List<?> values = redis.execute(SNAPSHOT, keys);
        if (values == null) throw busy();
        if (values.size() == 1 && "BUSY".equals(values.getFirst())) return null;
        return values.stream().map(String::valueOf).toList();
    }

    void publish(String key, List<String> tables, List<String> versions, JSONObject value, long ttl) {
        String json = JSON.toJSONString(value, JSONWriter.Feature.WriteNulls);
        // 大响应仍覆盖缓存，通过指标提示容量评估，不能悄悄变成每次回源。
        if (json.getBytes(StandardCharsets.UTF_8).length > properties.getLargeValueBytes())
            count("payload", "large");
        List<String> keys = new ArrayList<>(List.of(key));
        tables.forEach(t -> { keys.add(versionKey(t)); keys.add(writerKey(t)); keys.add(indexKey(t)); });
        List<String> arguments = new ArrayList<>(List.of(json, Long.toString(ttl)));
        arguments.addAll(versions);
        redis.execute(PUBLISH, keys, arguments.toArray());
    }

    /** 先栅栏和换代，再物理删除；任何 Redis 失败必须阻止后续 SQL。 */
    public void beforeWrite(String table, String token) {
        require(redis.execute(BEGIN, List.of(versionKey(table), writerKey(table)), UUID.randomUUID().toString(), token));
        deleteIndexed(table);
    }

    /** 完成物理清理之后才释放栅栏，回滚同样需要使旧读取失效。 */
    public void afterWrite(String table, String token) {
        deleteIndexed(table);
        require(redis.execute(FINISH, List.of(versionKey(table), writerKey(table)), UUID.randomUUID().toString(), token));
    }

    private void deleteIndexed(String table) {
        // 栅栏阻止新结果发布；分批读删，避免一次把整个失效索引拉入 JVM。
        while (true) {
            Set<String> keys = redis.opsForZSet().range(indexKey(table), 0, 199);
            if (keys == null || keys.isEmpty()) return;
            List<String> batch = new ArrayList<>(keys);
            redis.delete(batch);
            redis.opsForZSet().remove(indexKey(table), batch.toArray());
        }
    }

    private void require(Long result) { if (!Long.valueOf(1).equals(result)) throw busy(); }
    private long negativeTtl() { return jitter(properties.getNegativeSeconds()*1000L); }
    // 向上抖动，业务基础 TTL 不被缩短；价格与邀请码等时间语义在读取时计算。
    long jitter(long ttl) { return Math.max(1, ttl+ThreadLocalRandom.current().nextLong(Math.max(1,ttl/5+1))); }
    private void pause() throws InterruptedException { Thread.sleep(20); }
    private void count(String name, String outcome) { metrics.counter("verse.query.cache", "query", name, "outcome", outcome).increment(); }
    static ServerException busy() { return new ServerException("查询缓存繁忙，请稍后重试"); }
    private String scoped(String keyPrefix) {
        return keyPrefix + testScope;
    }
    String versionKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_VERSION_KEY)+table; }
    String writerKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_WRITERS_KEY)+table; }
    String indexKey(String table) { return scoped(RedisKeyConstant.CORE_QUERY_CACHE_INDEX_KEY)+table; }
}
