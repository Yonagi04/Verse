package com.yonagi.verse.common.cache;

import com.alibaba.fastjson2.TypeReference;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis/Lua/Redisson 测试，仅操作带独立随机测试后缀的键。 */
@EnabledIfSystemProperty(named="query.cache.redis-it", matches="true")
class QueryCacheRedisTest {
    private static RedissonConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedissonClient client;
    private String testScope;
    private QueryCacheProperties properties;
    private QueryCache cache;
    @BeforeAll static void connect() throws Exception {
        Config config=new Config(); config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        client=Redisson.create(config);
        factory=new RedissonConnectionFactory(client); factory.afterPropertiesSet();
        redis=new StringRedisTemplate(factory);
    }
    @AfterAll static void close() throws Exception { if(client!=null)client.shutdown(); if(factory!=null)factory.destroy(); }
    @BeforeEach void create() {
        testScope="test-"+UUID.randomUUID()+":";
        properties=new QueryCacheProperties(); properties.setMaxConcurrency(64);
        properties.setWaitMillis(1500); cache=new QueryCache(redis,client,properties,new SimpleMeterRegistry(),testScope);
    }
    @AfterEach void clear() {
        List<String> keys=new ArrayList<>();
        try(var cursor=redis.scan(ScanOptions.scanOptions().match("verse:*:"+testScope+"*").count(100).build())) { cursor.forEachRemaining(keys::add); }
        if(!keys.isEmpty())redis.delete(keys);
    }
    private String read(String key,java.util.function.Supplier<String> loader) {
        return cache.read("test",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"test:",key,String.class,List.of("t_test"),10000,loader);
    }
    @Test void hotMissAcrossTwoInstancesBuildsOnce() throws Exception {
        QueryCache second=new QueryCache(redis,client,properties,new SimpleMeterRegistry(),testScope);
        AtomicInteger loads=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(16)) {
            CountDownLatch start=new CountDownLatch(1); List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<16;i++) {
                QueryCache instance=i%2==0?cache:second;
                futures.add(pool.submit(()->{start.await(); return instance.read("hot",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"hot:","same",String.class,List.of("t_test"),10000,
                        ()->{loads.incrementAndGet(); sleep(120); return "value";});}));
            }
            start.countDown(); for(var future:futures)assertEquals("value",future.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,loads.get());
    }
    @Test void nullEmptyAndNotFoundAreNegativeCached() throws Throwable {
        AtomicInteger loads=new AtomicInteger();
        for(int i=0;i<3;i++)assertNull(read("absent",()->{loads.incrementAndGet();return null;}));
        assertEquals(1,loads.get());
        for(int i=0;i<3;i++) {
            ClientException error=assertThrows(ClientException.class,()->cache.get("missing",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"missing:","id",String.class,List.of("t_test"),10000,
                    ()->{},()->{loads.incrementAndGet();throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);},value->true));
            assertEquals(UserErrorCodeEnum.USER_NOT_EXIST.code(),error.getErrorCode());
        }
        assertEquals(2,loads.get());
        for(int i=0;i<3;i++)assertEquals(List.of(),cache.get("empty",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"empty:","id",new TypeReference<List<String>>(){}.getType(),List.of("t_test"),10000,
                ()->{},()->{loads.incrementAndGet();return List.of();},value->true));
        assertEquals(3,loads.get());
    }
    @Test void deploymentRedisPolicyMustProtectLocksAndFences() {
        Properties configuration = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>)
                connection -> connection.serverCommands().getConfig("maxmemory-policy"));
        assertNotNull(configuration);
        assertEquals("noeviction", configuration.getProperty("maxmemory-policy"), "部署前必须确认写栅栏和锁不会被内存淘汰");
    }

    @Test void newWriteImmediatelyInvalidatesNegativeCache() {
        assertNull(read("new-resource", () -> null));
        cache.beforeWrite("t_test", "create"); cache.afterWrite("t_test", "create");
        assertEquals("created", read("new-resource", () -> "created"));
    }

    @Test void deletesBeforeWriteAndOldLoaderCannotPublish() throws Exception {
        read("warm",()->"old"); String key=redis.opsForZSet().range(cache.indexKey("t_test"),0,-1).iterator().next();
        cache.beforeWrite("t_test","writer"); assertFalse(Boolean.TRUE.equals(redis.hasKey(key))); assertNull(cache.snapshot(List.of("t_test")));
        cache.afterWrite("t_test","writer"); assertEquals("new",read("warm",()->"new"));
        CountDownLatch loaded=new CountDownLatch(1),release=new CountDownLatch(1); AtomicInteger count=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<String> result=pool.submit(()->read("racing",()->{
                if(count.incrementAndGet()==1){loaded.countDown();await(release);return "stale";}return "fresh";
            }));
            assertTrue(loaded.await(2,TimeUnit.SECONDS)); cache.beforeWrite("t_test","writer2"); cache.afterWrite("t_test","writer2");release.countDown();
            assertEquals("fresh",result.get(4,TimeUnit.SECONDS));
        }
        assertEquals("fresh",read("racing",()->{fail("must reuse fresh cache");return null;}));
    }
    @Test void pendingWritersTimeoutWithoutDatabaseFallback() {
        properties.setWaitMillis(120);cache=new QueryCache(redis,client,properties,new SimpleMeterRegistry(),testScope);
        cache.beforeWrite("t_test","one");cache.beforeWrite("t_test","two");cache.afterWrite("t_test","one");
        assertNull(cache.snapshot(List.of("t_test")));
        assertThrows(ServerException.class,()->read("id",()->{fail("must not bypass fence");return null;}));
        cache.afterWrite("t_test","two");assertEquals("ok",read("id",()->"ok"));
    }
    @Test void avalancheLimitsDifferentKeyLoaders() throws Exception {
        properties.setMaxConcurrency(2);cache=new QueryCache(redis,client,properties,new SimpleMeterRegistry(),testScope);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),rejected=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(12)) {
            List<Future<?>> results=new ArrayList<>();
            for(int i=0;i<12;i++){String key="cold"+i;results.add(pool.submit(()->{
                try{read(key,()->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);entered.countDown();await(release);active.decrementAndGet();return "ok";});}
                catch(ServerException expected){rejected.incrementAndGet();}
            }));}
            assertTrue(entered.await(2,TimeUnit.SECONDS));sleep(150);release.countDown();for(var result:results)result.get(3,TimeUnit.SECONDS);
        }
        assertEquals(2,peak.get());assertTrue(rejected.get()>0);
    }
    @Test void genericRoundTripAndAuthorizationRunsOnHit() throws Throwable {
        var type=new TypeReference<List<Item>>(){}.getType();AtomicInteger loads=new AtomicInteger(),checks=new AtomicInteger();
        for(int i=0;i<2;i++)assertEquals(List.of(new Item(7L,"模型")),cache.get("generic",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"generic:",List.of(10L,20L,"MEMBER"),type,List.of("t_test"),10000,
                checks::incrementAndGet,()->{loads.incrementAndGet();return List.of(new Item(7L,"模型"));},value->true));
        assertEquals(1,loads.get());assertEquals(2,checks.get());
        assertThrows(ClientException.class,()->cache.get("generic",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"generic:",List.of(10L,20L,"MEMBER"),type,List.of("t_test"),10000,
                ()->{throw new ClientException("revoked");},()->List.of(),value->true));
    }
    @Test void ttlIsSpreadAndNeverFallsBelowConfiguredBaseline() {
        Set<Long> ttls=new HashSet<>();for(int i=0;i<100;i++){long ttl=cache.jitter(10000);assertTrue(ttl>=10000&&ttl<=12000);ttls.add(ttl);}assertTrue(ttls.size()>20);
    }
    @Test void corruptEnvelopeRebuildsUnderLock() {
        AtomicInteger loads=new AtomicInteger();read("corrupt",()->"initial");
        String key=redis.opsForZSet().range(cache.indexKey("t_test"),0,-1).iterator().next();redis.opsForValue().set(key,"broken-json");
        assertEquals("rebuilt",read("corrupt",()->{loads.incrementAndGet();return "rebuilt";}));assertEquals(1,loads.get());
    }
    @Test void actualServiceProxyCachesModelsAndSeparatesTenants() {
        var metadata = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "proxy-cache");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(metadata, com.yonagi.verse.dao.entity.LlmServiceDO.class);
        var mapper = org.mockito.Mockito.mock(com.yonagi.verse.dao.mapper.LlmServiceMapper.class);
        var service = com.yonagi.verse.dao.entity.LlmServiceDO.builder().serviceId(10L).tenantId(20L)
                .name("alias").provider("custom").status(1).build(); service.setDelFlag(0);
        org.mockito.Mockito.when(mapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(service, service, null);
        var resolver = new com.yonagi.verse.service.forward.impl.ModelResolverImpl(redis, mapper,
                org.mockito.Mockito.mock(com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper.class),
                new com.yonagi.verse.service.forward.AdapterRegistry(List.of()));
        var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(resolver);
        factory.addAspect(new CoreQueryCacheAspect(cache, org.mockito.Mockito.mock(QueryAccessGuard.class)));
        com.yonagi.verse.service.forward.ModelResolver proxy = factory.getProxy();
        assertEquals("custom", proxy.resolve(20L, "alias").getProvider());
        assertEquals("custom", proxy.resolve(20L, "alias").getProvider());
        assertThrows(ClientException.class, () -> proxy.resolve(30L, "alias"));
        assertThrows(ClientException.class, () -> proxy.resolve(30L, "alias"));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(3)).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test void cachedPricingRulesRespectPeakAndHistoricalBoundaries() {
        var metadata = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "pricing-cache");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(metadata, com.yonagi.verse.dao.entity.LlmServicePricingDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(metadata, com.yonagi.verse.dao.entity.LlmPricingPeakPeriodDO.class);
        var current = new com.yonagi.verse.dao.entity.LlmServicePricingDO();
        current.setPricingId(2L); current.setTenantId(20L); current.setServiceId(10L);
        current.setBillingMode("REQUEST"); current.setCurrency("CNY"); current.setBaseRequestPriceFen(java.math.BigDecimal.ONE);
        current.setEffectiveFrom(java.time.LocalDateTime.of(2026, 10, 5, 0, 0));
        var old = new com.yonagi.verse.dao.entity.LlmServicePricingDO();
        old.setPricingId(1L); old.setBillingMode("REQUEST"); old.setCurrency("CNY"); old.setBaseRequestPriceFen(new java.math.BigDecimal("2"));
        old.setEffectiveFrom(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)); old.setEffectiveTo(current.getEffectiveFrom());
        var peak = new com.yonagi.verse.dao.entity.LlmPricingPeakPeriodDO(); peak.setPricingId(2L); peak.setPeriodId(3L);
        peak.setWeekdayMask(1); peak.setStartMinute(540); peak.setEndMinute(600); peak.setPeakRequestPriceFen(java.math.BigDecimal.TEN);
        var prices = org.mockito.Mockito.mock(com.yonagi.verse.dao.mapper.LlmServicePricingMapper.class);
        var peaks = org.mockito.Mockito.mock(com.yonagi.verse.dao.mapper.LlmPricingPeakPeriodMapper.class);
        org.mockito.Mockito.when(prices.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(current, old));
        org.mockito.Mockito.when(peaks.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(peak));
        var resolver = new com.yonagi.verse.service.pricing.PricingResolver(prices, peaks, cache);
        var zone = com.yonagi.verse.service.pricing.PricingResolver.SHANGHAI;
        assertEquals(java.math.BigDecimal.ONE, resolver.resolve(20L, 10L, java.time.LocalDateTime.of(2026, 10, 5, 8, 59).atZone(zone).toInstant()).requestPriceFen());
        assertEquals(java.math.BigDecimal.TEN, resolver.resolve(20L, 10L, java.time.LocalDateTime.of(2026, 10, 5, 9, 0).atZone(zone).toInstant()).requestPriceFen());
        assertEquals(java.math.BigDecimal.ONE, resolver.resolve(20L, 10L, java.time.LocalDateTime.of(2026, 10, 5, 10, 0).atZone(zone).toInstant()).requestPriceFen());
        assertEquals(new java.math.BigDecimal("2"), resolver.resolve(20L, 10L, java.time.LocalDateTime.of(2026, 2, 1, 0, 0).atZone(zone).toInstant()).requestPriceFen());
        org.mockito.Mockito.verify(prices).selectList(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(peaks).selectList(org.mockito.ArgumentMatchers.any());
    }

    @Test void springTransactionCommitAndRollbackKeepFenceUntilCompletion() throws Exception {
        var database = new org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2).build();
        try {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database);
            jdbc.execute("CREATE TABLE t_tenant (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            jdbc.update("INSERT INTO t_tenant VALUES (1, 'old')");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(database));
            java.util.function.Supplier<String> query = () -> jdbc.queryForObject("SELECT name FROM t_tenant WHERE id=1", String.class);
            java.util.function.Supplier<String> cached = () -> cache.read("transaction",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"transaction:", 1L, String.class, List.of("t_tenant"), 10000, query);
            assertEquals("old", cached.get());
            var db = org.mockito.Mockito.mock(org.apache.ibatis.executor.Executor.class);
            org.mockito.Mockito.when(db.update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                assertNull(cache.snapshot(List.of("t_tenant")));
                assertTrue(redis.opsForZSet().range(cache.indexKey("t_tenant"), 0, -1).isEmpty());
                org.apache.ibatis.mapping.MappedStatement statement = call.getArgument(0);
                return jdbc.update(statement.getBoundSql(null).getSql());
            });
            var executor = (org.apache.ibatis.executor.Executor) new QueryWriteInterceptor(cache).plugin(db);
            for (boolean rollback : List.of(false, true)) {
                transaction.executeWithoutResult(status -> {
                    var config = new org.apache.ibatis.session.Configuration();
                    String value = rollback ? "rolled-back" : "committed";
                    var statement = new org.apache.ibatis.mapping.MappedStatement.Builder(config, "test.transaction",
                            new org.apache.ibatis.builder.StaticSqlSource(config, "UPDATE t_tenant SET name='"+value+"' WHERE id=1"),
                            org.apache.ibatis.mapping.SqlCommandType.UPDATE).build();
                    try { executor.update(statement, null); } catch (Exception error) { throw new RuntimeException(error); }
                    assertEquals(value, cached.get(), "事务内读取直接看到本事务写入，不能等待自己的栅栏");
                    assertNull(cache.snapshot(List.of("t_tenant")));
                    if (rollback) status.setRollbackOnly();
                });
                assertNotNull(cache.snapshot(List.of("t_tenant")));
                assertEquals("committed", cached.get());
            }
        } finally { database.shutdown(); }
    }

    public record Item(Long id,String name){}
    private static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(4,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
}
