package com.yonagi.verse.common.cache;

import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import com.yonagi.verse.service.tenant.TenantQueryAccess;

import com.yonagi.verse.support.MySqlTestDatabase;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.budget.CostBudgetService;
import com.yonagi.verse.service.impl.ApiKeyServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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
        for (Class<?> type : List.of(ApiKeyDO.class, TenantDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "key-list-cache"), type);
        Config config=new Config(); config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress(System.getProperty("query.cache.redis-address", "redis://127.0.0.1:6379"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
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
    @Test void persistedCompletionRecoversAfterRestartAndPreservesOldActiveWriter() {
        assertEquals("old", read("recovery", () -> "old"));
        cache.beforeWrite("t_test", "ended");
        cache.beforeWrite("t_test", "active");
        redis.opsForZSet().add(cache.writerCreatedKey("t_test"), "active", System.currentTimeMillis() - 3600000);
        assertEquals(-1, redis.getExpire(cache.writerKey("t_test")));
        assertNotNull(redis.opsForHash().get(cache.writerOwnerKey("t_test"), "active"));
        var failing = spy(cache);
        doThrow(new IllegalStateException("cleanup failed after recording completion")).when(failing).cleanupCompleted("t_test", "ended");
        assertThrows(IllegalStateException.class, () -> failing.afterWrite("t_test", "ended"));
        assertNotNull(redis.opsForZSet().score(cache.writerCompletedKey("t_test"), "ended"));
        var metrics = new SimpleMeterRegistry();
        var restarted = new QueryCache(redis, client, properties, metrics, testScope);
        var catalogue = new QueryCatalogue(); catalogue.register(QueryFenceRecoveryTest.Queries.class);
        new QueryFenceRecovery(restarted, catalogue, properties, metrics).recover();
        assertEquals(Set.of("active"), redis.opsForSet().members(cache.writerKey("t_test")));
        assertNull(cache.snapshot(List.of("t_test")));
        assertEquals(1, metrics.get("verse.query.cache.fence.writers").tag("table", "t_test").gauge().value());
        assertTrue(metrics.get("verse.query.cache.fence.oldest.seconds").tag("table", "t_test").gauge().value() >= 3600);
        assertFalse(restarted.cleanupCompleted("t_test", "active"));
        assertEquals(-1, redis.getExpire(cache.writerKey("t_test")));
        cache.afterWrite("t_test", "active");
        assertEquals("new", read("recovery", () -> "new"));
    }

    @Test void cleanupIsBoundedAndFenceRemainsUntilAllIndexedValuesAreDeleted() {
        properties.setFenceCleanupBatches(1);
        cache.beforeWrite("t_test", "ended");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 401; i++) {
            String key = RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY + testScope + "stale:" + i;
            keys.add(key); redis.opsForValue().set(key, "stale");
            redis.opsForZSet().add(cache.indexKey("t_test"), key, System.currentTimeMillis() + 600000);
        }
        assertFalse(cache.afterWrite("t_test", "ended"));
        assertNull(cache.snapshot(List.of("t_test")));
        assertEquals(201, redis.opsForZSet().size(cache.indexKey("t_test")));
        assertFalse(cache.cleanupCompleted("t_test", "ended"));
        assertNull(cache.snapshot(List.of("t_test")));
        assertTrue(cache.cleanupCompleted("t_test", "ended"));
        List<String> versions = cache.snapshot(List.of("t_test"));
        assertNotNull(versions);
        assertEquals(0, redis.countExistingKeys(keys));
        assertTrue(cache.afterWrite("t_test", "ended"), "重复完成不会再次污染代际或产生残留");
        assertEquals(versions, cache.snapshot(List.of("t_test")));
        assertEquals("fresh", read("bounded", () -> "fresh"));
    }

    @Test void realMybatisBatchSessionKeepsFenceUntilCommitOrRollback() throws Exception {
        var database = MySqlTestDatabase.create();
        try {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database);
            jdbc.execute("CREATE TABLE t_tenant (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            jdbc.update("INSERT INTO t_tenant VALUES (1, 'old')");
            var configuration = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                    "cache-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), database));
            configuration.addInterceptor(QueryCacheTestSupport.interceptor(cache));
            configuration.addMappedStatement(new org.apache.ibatis.mapping.MappedStatement.Builder(configuration, "test.batch",
                    new org.apache.ibatis.builder.StaticSqlSource(configuration, "UPDATE t_tenant SET name='new' WHERE id=1"),
                    org.apache.ibatis.mapping.SqlCommandType.UPDATE).build());
            var factory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
            for (boolean rollback : List.of(true, false)) {
                try (var session = factory.openSession(org.apache.ibatis.session.ExecutorType.BATCH, false)) {
                    session.update("test.batch");
                    assertNull(cache.snapshot(List.of("t_tenant")), "批处理尚未发送，必须保留栅栏");
                    assertEquals("old", jdbc.queryForObject("SELECT name FROM t_tenant WHERE id=1", String.class));
                    if (rollback) session.rollback(); else session.commit();
                    assertNotNull(cache.snapshot(List.of("t_tenant")));
                }
                assertEquals(rollback ? "old" : "new", jdbc.queryForObject("SELECT name FROM t_tenant WHERE id=1", String.class));
            }
        } finally { database.close(); }
    }
    @Test void maintenanceRecoveryRequiresEvidenceAndStableGenerationBeforeFullInvalidation() throws Exception {
        cache.beforeWrite("t_test", "orphan");
        String version = redis.opsForValue().get(cache.versionKey("t_test"));
        List<String> keys = List.of(cache.versionKey("t_test"), cache.writerKey("t_test"), cache.writerCreatedKey("t_test"),
                cache.writerOwnerKey("t_test"), cache.writerCompletedKey("t_test"), cache.indexKey("t_test"));
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                java.nio.file.Files.readString(java.nio.file.Path.of("scripts/recover-query-cache.lua")), List.class);
        List<?> dryRun = redis.execute(script, keys, "dry-run");
        assertEquals("DRY_RUN", dryRun.getFirst());
        assertEquals(version, dryRun.get(1));
        assertNull(cache.snapshot(List.of("t_test")));
        String expected = version, replacement = UUID.randomUUID().toString();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> redis.execute(script, keys, "apply", "", expected, replacement));
        cache.beforeWrite("t_test", "another-writer");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> redis.execute(script, keys, "apply",
                "WRITERS_STOPPED_AND_DB_TRANSACTIONS_ENDED", expected, replacement));
        String verified = redis.opsForValue().get(cache.versionKey("t_test"));
        List<String> stale = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            String key = RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY + testScope + "maintenance:" + i;
            stale.add(key); redis.opsForValue().set(key, "stale");
            redis.opsForZSet().add(cache.indexKey("t_test"), key, System.currentTimeMillis() + 600000);
        }
        List<?> partial = redis.execute(script, keys, "apply", "WRITERS_STOPPED_AND_DB_TRANSACTIONS_ENDED", verified, replacement);
        assertEquals("PENDING", partial.getFirst());
        assertNull(cache.snapshot(List.of("t_test")));
        List<?> done = redis.execute(script, keys, "apply", "WRITERS_STOPPED_AND_DB_TRANSACTIONS_ENDED", verified, replacement);
        assertEquals("RECOVERED", done.getFirst());
        assertEquals(List.of(replacement), cache.snapshot(List.of("t_test")));
        assertEquals(0, redis.countExistingKeys(stale));
        assertEquals(0, redis.countExistingKeys(keys.subList(1, keys.size())));
        assertEquals("new", read("maintenance", () -> "new"));
    }
    @Test void missingWorkbenchKindIsNegativeCachedAndCreationInvalidatesIt() {
        var tenants = mock(TenantAccessPolicy.class);
        var tenant = new TenantDO(); tenant.setPlaygroundEnabled(1);
        when(tenants.requireContext(any(), eq(20L))).thenReturn(tenant);
        var workspaces = mock(PlaygroundWorkspaceMapper.class);
        var access = new com.yonagi.verse.service.playground.PlaygroundAccessPolicy(tenants, workspaces);
        var behavior = new com.yonagi.verse.service.playground.WorkbenchDetailCacheBehavior(access, cache);
        var actor = new com.yonagi.verse.common.security.UserContext().setUserId(10L).setCurrentTenantId(20L);
        Object[] args = {actor, 20L, 30L};
        for (int i = 0; i < 3; i++) {
            ClientException error = assertThrows(ClientException.class, () -> behavior.supports(args));
            assertEquals(com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum.SESSION_NOT_FOUND.code(), error.getErrorCode());
        }
        verify(workspaces, times(1)).selectOne(any());
        String key = redis.opsForZSet().range(cache.indexKey("t_playground_workspace"), 0, -1).iterator().next();
        assertNull(JSON.parseObject(redis.opsForValue().get(key)).get("value"));
        assertTrue(redis.getExpire(key, TimeUnit.SECONDS) <= properties.getNegativeSeconds() * 1.2);
        actor.setApiKeyId(99L);
        assertThrows(ClientException.class, () -> behavior.supports(args));
        verify(workspaces, times(1)).selectOne(any());
        actor.setApiKeyId(null);
        var workspace = new PlaygroundWorkspaceDO(); workspace.setKind("PRESET");
        when(workspaces.selectOne(any())).thenReturn(workspace);
        cache.beforeWrite("t_playground_workspace", "create");
        cache.afterWrite("t_playground_workspace", "create");
        assertTrue(behavior.supports(args));
        verify(workspaces, times(2)).selectOne(any());
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
        assertEquals(4,loads.get(), "业务异常不作为授权结论缓存");
        for(int i=0;i<3;i++)assertEquals(List.of(),cache.get("empty",RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY+"empty:","id",new TypeReference<List<String>>(){}.getType(),List.of("t_test"),10000,
                ()->{},()->{loads.incrementAndGet();return List.of();},value->true));
        assertEquals(5,loads.get());
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

    @Test void playgroundIdentityRulesProtectWarmAndColdResults() throws Throwable {
        var tenants = mock(TenantMapper.class);
        var members = mock(UserTenantMapper.class);
        TenantDO tenant = new TenantDO(); tenant.setPlaygroundEnabled(1);
        UserTenantDO member = new UserTenantDO(); member.setRole("ADMIN");
        when(tenants.selectOne(any())).thenReturn(tenant);
        when(members.selectOne(any())).thenReturn(member);
        var domain = new com.yonagi.verse.service.playground.PlaygroundAccessPolicy(
                new TenantAccessPolicy(tenants, members), mock(PlaygroundWorkspaceMapper.class));
        var access = new com.yonagi.verse.service.playground.PlaygroundQueryAccess(domain);
        var actor = new com.yonagi.verse.common.security.UserContext()
                .setUserId(10L).setCurrentTenantId(20L).setRole("ADMIN");
        AtomicInteger loads = new AtomicInteger();
        Runnable check = () -> access.check(new Object[]{actor, 20L});
        String prefix = RedisKeyConstant.CORE_QUERY_CACHE_TEST_KEY + "identity:";
        assertEquals("private", cache.get("identity", prefix, "warm", String.class, List.of("t_test"), 10000,
                check, () -> { loads.incrementAndGet(); return "private"; }, value -> true));
        actor.setApiKeyId(99L);
        for (String key : List.of("warm", "cold")) {
            ClientException error = assertThrows(ClientException.class, () -> cache.get("identity", prefix, key,
                    String.class, List.of("t_test"), 10000, check,
                    () -> { loads.incrementAndGet(); return "private"; }, value -> true));
            assertEquals(com.yonagi.verse.common.enums.TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH.code(), error.getErrorCode());
        }
        assertThrows(ClientException.class, () -> domain.requireEnabled(actor, 20L));
        assertEquals(1, loads.get());
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
        org.mockito.Mockito.when(mapper.countCallableService(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);
        var service = com.yonagi.verse.dao.entity.LlmServiceDO.builder().serviceId(10L).tenantId(20L)
                .name("alias").provider("custom").status(1).build(); service.setDelFlag(0);
        org.mockito.Mockito.when(mapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(service, service, null);
        var resolver = new com.yonagi.verse.service.forward.impl.ModelResolverImpl(redis, mapper,
                org.mockito.Mockito.mock(com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper.class),
                new com.yonagi.verse.service.forward.AdapterRegistry(List.of()));
        var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(resolver);
        factory.addAspect(QueryCacheTestSupport.aspect(cache));
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
        var database = MySqlTestDatabase.create();
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
            var executor = (org.apache.ibatis.executor.Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
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
        } finally { database.close(); }
    }

    @Test void keyListCachesMetadataButRefreshesUsageAndNaturalExpiry() {
        var fixture = new KeyListFixture();
        fixture.key.setExpiresAt(new Date(System.currentTimeMillis() - 1000));
        var first = fixture.service.listApiKeys(10L, 20L, 1, 10);
        assertEquals(2, first.getRecords().getFirst().getStatus());
        fixture.usedAt = new Date(2000);
        var second = fixture.service.listApiKeys(10L, 20L, 1, 10);
        assertEquals(new Date(2000), second.getRecords().getFirst().getLastUsedAt());
        assertEquals(2, second.getRecords().getFirst().getStatus());
        verify(fixture.mapper, times(1)).selectPage(any(Page.class), any());
        verify(fixture.mapper, times(2)).selectList(any());
        verify(fixture.mapper, never()).update(any(), any());
        String key = redis.opsForZSet().range(cache.indexKey("t_api_key"), 0, -1).iterator().next();
        var metadata = JSON.parseObject(redis.opsForValue().get(key)).getJSONObject("value").getJSONArray("records").getJSONObject(0);
        assertEquals(1, metadata.getInteger("status"));
        assertNull(metadata.get("lastUsedAt"));
        assertFalse(metadata.containsKey("apiKey"));
        assertEquals(new Date(1000), first.getRecords().getFirst().getLastUsedAt(), "补充最新状态不修改已有响应");
    }

    @Test void keyListCacheSeparatesUserTenantAndPagination() {
        var fixture = new KeyListFixture();
        fixture.service.listApiKeys(10L, 20L, 1, 10);
        fixture.service.listApiKeys(10L, 20L, 1, 10);
        fixture.service.listApiKeys(11L, 20L, 1, 10);
        fixture.service.listApiKeys(10L, 21L, 1, 10);
        fixture.service.listApiKeys(10L, 20L, 2, 10);
        fixture.service.listApiKeys(10L, 20L, 1, 20);
        verify(fixture.mapper, times(5)).selectPage(any(Page.class), any());
        assertEquals(5, redis.opsForZSet().size(cache.indexKey("t_api_key")));
    }

    @Test void keyListCreatesEditsAndRevokesInvalidateWarmResults() throws Exception {
        var fixture = new KeyListFixture();
        fixture.key.setStatus(0);
        assertTrue(fixture.service.listApiKeys(10L, 20L, 1, 10).getRecords().isEmpty());
        var db = mock(org.apache.ibatis.executor.Executor.class);
        when(db.update(any(), any())).thenReturn(1);
        var executor = (org.apache.ibatis.executor.Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        List<String> mutations = List.of("INSERT INTO t_api_key (name) VALUES ('created')",
                "UPDATE t_api_key SET cost_config_version=1", "UPDATE t_api_key SET status=0");
        for (int i = 0; i < mutations.size(); i++) {
            fixture.key.setStatus(i == 2 ? 0 : 1);
            fixture.key.setName(i == 0 ? "created" : "edited");
            fixture.key.setCostConfigVersion((long) i);
            var configuration = new org.apache.ibatis.session.Configuration();
            var statement = new org.apache.ibatis.mapping.MappedStatement.Builder(configuration, "test.key-list",
                    new org.apache.ibatis.builder.StaticSqlSource(configuration, mutations.get(i)),
                    i == 0 ? org.apache.ibatis.mapping.SqlCommandType.INSERT : org.apache.ibatis.mapping.SqlCommandType.UPDATE).build();
            executor.update(statement, null);
            executor.commit(true);
            var result = fixture.service.listApiKeys(10L, 20L, 1, 10);
            if (i == 2) assertTrue(result.getRecords().isEmpty());
            else {
                assertEquals(fixture.key.getName(), result.getRecords().getFirst().getName());
                assertEquals(String.valueOf(i), result.getRecords().getFirst().getCostLimit().version());
            }
        }
        verify(fixture.mapper, times(4)).selectPage(any(Page.class), any());
    }

    @Test void warmKeyListStillRejectsRemovedMembershipAndDisabledTenant() {
        var fixture = new KeyListFixture();
        fixture.service.listApiKeys(10L, 20L, 1, 10);
        when(fixture.memberships.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> fixture.service.listApiKeys(10L, 20L, 1, 10));
        when(fixture.memberships.selectOne(any())).thenReturn(new UserTenantDO());
        when(fixture.tenants.selectOne(any())).thenReturn(null);
        assertThrows(ClientException.class, () -> fixture.service.listApiKeys(10L, 20L, 1, 10));
        verify(fixture.mapper, times(1)).selectPage(any(Page.class), any());
        verify(fixture.mapper, times(1)).selectList(any());
    }

    @Test void keyListSqlFailureIsNotMisreportedAsRedisFailure() {
        var fixture = new KeyListFixture();
        var sql = new org.springframework.jdbc.BadSqlGrammarException("key-list", "SELECT cost_config_version",
                new java.sql.SQLException("Unknown column cost_config_version", "42S22", 1054));
        when(fixture.mapper.selectPage(any(Page.class), any())).thenThrow(sql);
        ServerException error = assertThrows(ServerException.class, () -> fixture.service.listApiKeys(10L, 20L, 1, 10));
        assertSame(sql, error.getCause());
        assertEquals("数据查询失败，请稍后重试", error.getErrorMessage());
        verify(fixture.mapper, times(1)).selectPage(any(Page.class), any());
    }

    @Test void transactionalKeyListBypassesWarmCache() {
        var fixture = new KeyListFixture();
        fixture.service.listApiKeys(10L, 20L, 1, 10);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            fixture.key.setName("transaction-value");
            assertEquals("transaction-value", fixture.service.listApiKeys(10L, 20L, 1, 10).getRecords().getFirst().getName());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(fixture.mapper, times(2)).selectPage(any(Page.class), any());
    }

    @Test void notificationListCachesRepeatedFiltersAndSeparatesEveryQueryParameter() {
        var fixture = new NotificationListFixture();
        var query = new com.yonagi.verse.dto.req.NotificationListReqDTO();
        fixture.service.getNotificationList(10L, query);
        // 相同字段的新 DTO 也应命中缓存，而不是依赖对象身份。
        fixture.service.getNotificationList(10L, new com.yonagi.verse.dto.req.NotificationListReqDTO());
        verify(fixture.mapper, times(1)).selectPageByUserIdAndStartTime(any(Page.class), anyLong(), anyLong(), any());

        query.setType("SYSTEM");
        fixture.service.getNotificationList(10L, query);
        query.setSeverity("WARNING");
        fixture.service.getNotificationList(10L, query);
        query.setIsRead(0);
        var unread = fixture.service.getNotificationList(10L, query);
        assertFalse(unread.getRecords().getFirst().getIsRead());
        fixture.service.getNotificationList(10L, query);
        query.setIsRead(1);
        var read = fixture.service.getNotificationList(10L, query);
        assertTrue(read.getRecords().getFirst().getIsRead());
        query.setPageNum(2);
        fixture.service.getNotificationList(10L, query);
        query.setPageSize(20);
        fixture.service.getNotificationList(10L, query);
        fixture.service.getNotificationList(11L, query);
        fixture.service.getNotificationList(11L, query);
        verify(fixture.mapper, times(8)).selectPageByUserIdAndStartTime(any(Page.class), anyLong(), anyLong(), any());
        assertEquals(8, redis.opsForZSet().size(cache.indexKey("t_notification")));
    }

    @Test void notificationWritesInvalidateFilteredListCaches() throws Exception {
        var fixture = new NotificationListFixture();
        var query = new com.yonagi.verse.dto.req.NotificationListReqDTO();
        query.setIsRead(0);
        fixture.service.getNotificationList(10L, query);
        fixture.service.getNotificationList(10L, query);
        var db = mock(org.apache.ibatis.executor.Executor.class);
        when(db.update(any(), any())).thenReturn(1);
        var executor = (org.apache.ibatis.executor.Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        // 接收状态更新和通知写入均应失效，确保已读操作与新通知刷新能获取新数据。
        for (String sql : List.of("UPDATE t_notification_recipient SET is_read=1 WHERE user_id=10",
                "INSERT INTO t_notification (title) VALUES ('new')")) {
            var configuration = new org.apache.ibatis.session.Configuration();
            var statement = new org.apache.ibatis.mapping.MappedStatement.Builder(configuration, "test.notifications",
                    new org.apache.ibatis.builder.StaticSqlSource(configuration, sql),
                    sql.startsWith("INSERT") ? org.apache.ibatis.mapping.SqlCommandType.INSERT : org.apache.ibatis.mapping.SqlCommandType.UPDATE).build();
            executor.update(statement, null);
            executor.commit(true);
            fixture.service.getNotificationList(10L, query);
            fixture.service.getNotificationList(10L, query);
        }
        verify(fixture.mapper, times(3)).selectPageByUserIdAndStartTime(any(Page.class), anyLong(), anyLong(), any());
    }

    /** 使用真实缓存切面、DTO 键序列化与 Redis，统计实际回源次数。 */
    private class NotificationListFixture {
        final NotificationRecipientMapper mapper = mock(NotificationRecipientMapper.class);
        final com.yonagi.verse.service.NotificationService service;

        NotificationListFixture() {
            when(mapper.selectPageByUserIdAndStartTime(any(Page.class), anyLong(), anyLong(), any())).thenAnswer(call -> {
                var query = (com.yonagi.verse.dto.req.NotificationListReqDTO) call.getArgument(3);
                var record = new com.yonagi.verse.dto.resp.NotificationListRespDTO.NotificationInfo()
                        .setNotificationId(30L).setType(query.getType()).setSeverity(query.getSeverity())
                        .setIsRead(Integer.valueOf(1).equals(query.getIsRead()));
                return new Page<com.yonagi.verse.dto.resp.NotificationListRespDTO.NotificationInfo>(query.getPageNum(), query.getPageSize())
                        .setTotal(1).setRecords(List.of(record));
            });
            var target = new com.yonagi.verse.service.impl.NotificationServiceImpl(mock(TenantAccessPolicy.class), mapper,
                    mock(com.yonagi.verse.async.api.DomainEventPublisher.class), cache,
                    mock(com.yonagi.verse.service.notification.NotificationCreationService.class));
            var proxy = new AspectJProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAspect(QueryCacheTestSupport.aspect(cache));
            service = proxy.getProxy();
        }
    }

    /** 使用真实切面和 Redis；Mapper 只提供可控列表事实，不模拟缓存行为。 */
    private class KeyListFixture {
        final ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        final TenantMapper tenants = mock(TenantMapper.class);
        final UserTenantMapper memberships = mock(UserTenantMapper.class);
        final ApiKeyDO key = new ApiKeyDO();
        final ApiKeyServiceImpl service;
        Date usedAt = new Date(1000);
        @SuppressWarnings("unchecked") KeyListFixture() {
            key.setApiKeyId(30L); key.setStatus(1); key.setName("original"); key.setKeyPrefix("sk_prefix");
            key.setApiKey("hash-must-not-be-cached"); key.setCostLimitEnabled(false); key.setCostConfigVersion(0L);
            when(tenants.selectOne(any())).thenReturn(new TenantDO());
            when(memberships.selectOne(any())).thenReturn(new UserTenantDO());
            var users = mock(UserTenantService.class);
            when(users.isUserJoinedTenant(anyLong(), anyLong())).thenReturn(true);
            when(mapper.selectPage(any(Page.class), any())).thenAnswer(call -> {
                Page<ApiKeyDO> page = call.getArgument(0);
                LambdaQueryWrapper<ApiKeyDO> query = call.getArgument(1);
                assertFalse(Arrays.asList(query.getSqlSelect().split(",")).contains("api_key"));
                assertFalse(query.getSqlSelect().contains("last_used_at"));
                page.setRecords(key.getStatus() == 0 ? List.of() : List.of(key));
                page.setTotal(page.getRecords().size()); return page;
            });
            when(mapper.selectList(any())).thenAnswer(call -> {
                LambdaQueryWrapper<ApiKeyDO> query = call.getArgument(0);
                assertTrue(query.getSqlSegment().contains("user_id"));
                assertTrue(query.getSqlSegment().contains("tenant_id"));
                ApiKeyDO current = new ApiKeyDO(); current.setApiKeyId(key.getApiKeyId()); current.setLastUsedAt(usedAt);
                return List.of(current);
            });
            var target = new ApiKeyServiceImpl(new TenantAccessPolicy(tenants, memberships),
                mock(CostBudgetService.class),
                tenants,
                users,
                redis);
            ReflectionTestUtils.setField(target, "baseMapper", mapper);
            var guard = new TenantQueryAccess(new TenantAccessPolicy(tenants, memberships));
            var proxy = new AspectJProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAspect(QueryCacheTestSupport.aspect(cache, guard)); service = proxy.getProxy();
        }
    }

    public record Item(Long id,String name){}
    private static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(4,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
}
