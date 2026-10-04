package com.yonagi.verse.common.cache;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 新增注解方法与业务策略即可获得缓存和写入失效，核心类无需增加分支。 */
class QueryCachedTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({QueryCatalogue.class, CoreQueryCacheAspect.class, QueryWriteInterceptor.class})
    static class CacheConfiguration { }

    private final QueryCache cache = mock(QueryCache.class);
    private final QueryAccessGuard guard = mock(QueryAccessGuard.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfiguration.class)
            .withBean(QueryCache.class, () -> cache)
            .withBean(QueryAccessGuard.class, () -> guard)
            .withBean(NewQueries.class)
            .withBean(Decorated.class);

    @Test void newAnnotatedBeanOutsideServicePackageCachesAndAutomaticallyInvalidatesItsTable() throws Throwable {
        Map<Object, Object> values = new HashMap<>();
        when(cache.get(anyString(), anyString(), any(), any(), anyList(), anyLong(), any(), any(), any()))
                .thenAnswer(call -> {
                    ((Runnable) call.getArgument(6)).run();
                    Object key = call.getArgument(2);
                    if (values.containsKey(key)) return values.get(key);
                    Object result = ((QueryCache.Loader) call.getArgument(7)).load();
                    if (((Predicate<Object>) call.getArgument(8)).test(result)) values.put(key, result);
                    return result;
                });
        context.run(application -> {
            assertThat(application).hasNotFailed();
            NewQueries query = application.getBean(NewQueries.class);
            assertEquals(List.of("item:7"), query.items(7L));
            assertEquals(List.of("item:7"), query.items(7L));
            assertEquals(1, query.loads());
            assertEquals("item:7", query.items((Number) 7L));
            assertEquals(2, query.loads());
            assertEquals(2, values.size(), "重载方法签名必须隔离缓存键");
            verify(guard, times(3)).check(any(), any());
            assertTrue(application.getBean(QueryCatalogue.class).dependsOn("t_extension_items"));

            Executor database = mock(Executor.class);
            doAnswer(call -> { values.clear(); return null; }).when(cache).beforeWrite(eq("t_extension_items"), anyString());
            Executor intercepted = (Executor) application.getBean(QueryWriteInterceptor.class).plugin(database);
            Configuration config = new Configuration();
            var statement = new MappedStatement.Builder(config, "test.newTable",
                    new StaticSqlSource(config, "UPDATE t_extension_items SET name='changed'"), SqlCommandType.UPDATE).build();
            intercepted.update(statement, null);
            var order = inOrder(cache, database);
            order.verify(cache).beforeWrite(eq("t_extension_items"), anyString());
            order.verify(database).update(statement, null);
            order.verify(cache).afterWrite(eq("t_extension_items"), anyString());
            query.items(7L);
            assertEquals(3, query.loads());
        });
    }

    @Test void customStrategyCanTransformLoadAndViewWithoutCoreChanges() throws Throwable {
        when(cache.get(anyString(), anyString(), any(), any(), anyList(), anyLong(), any(), any(), any()))
                .thenAnswer(call -> ((QueryCache.Loader) call.getArgument(7)).load());
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertEquals("view:ITEM", application.getBean(NewQueries.class).decorated("item"));
            assertEquals(1, application.getBean(Decorated.class).views.get());
        });
    }

    @Test void transactionAndUnannotatedMethodBypassCache() {
        context.run(application -> {
            NewQueries query = application.getBean(NewQueries.class);
            assertEquals("live", query.live());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertEquals(List.of("item:7"), query.items(7L)); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            verifyNoInteractions(cache, guard);
        });
    }

    @Test void metadataDiscoveryDoesNotInstantiateLazyBusinessBean() {
        var beans = new DefaultListableBeanFactory();
        var definition = new RootBeanDefinition(NewQueries.class);
        definition.setInstanceSupplier(() -> { throw new AssertionError("发现元数据不能创建服务"); });
        beans.registerBeanDefinition("lazyQuery", definition);
        QueryCatalogue catalogue = new QueryCatalogue();
        catalogue.postProcessBeanFactory(beans);
        assertTrue(catalogue.dependsOn("t_extension_items"));
        assertEquals(3, catalogue.policies().size());
        assertFalse(beans.containsSingleton("lazyQuery"));
    }

    @Test void unproxyableAnnotatedMethodsFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new QueryCatalogue().register(FinalQuery.class));
        assertThrows(IllegalStateException.class, () -> new QueryCatalogue().register(PrivateQuery.class));
    }

    public static class NewQueries {
        private final AtomicInteger loads = new AtomicInteger();
        @QueryCached(keyPrefix = "test:items:", seconds = 60, access = QueryCatalogue.Access.NONE, tables = "t_extension_items")
        public List<String> items(Long id) { loads.incrementAndGet(); return List.of("item:" + id); }
        @QueryCached(keyPrefix = "test:items:", seconds = 60, access = QueryCatalogue.Access.NONE, tables = "t_extension_items")
        public String items(Number id) { loads.incrementAndGet(); return "item:" + id; }
        @QueryCached(keyPrefix = "test:decorated:", seconds = 60, access = QueryCatalogue.Access.NONE,
                tables = "t_extension_items", behavior = Decorated.class)
        public String decorated(String value) { return value; }
        public String live() { return "live"; }
        public int loads() { return loads.get(); }
    }

    public static class Decorated implements QueryCacheBehavior {
        private final AtomicInteger views = new AtomicInteger();
        @Override public Object load(Object target, Object[] args, QueryCache.Loader original) throws Throwable {
            return ((String) original.load()).toUpperCase(Locale.ROOT);
        }
        @Override public Object currentView(Object target, Object[] args, Object cached) {
            views.incrementAndGet(); return "view:" + cached;
        }
    }

    static final class FinalQuery {
        @QueryCached(keyPrefix = "test:", seconds = 60, access = QueryCatalogue.Access.NONE, tables = "t_extension_items")
        public String value() { return "value"; }
    }
    static class PrivateQuery {
        @QueryCached(keyPrefix = "test:", seconds = 60, access = QueryCatalogue.Access.NONE, tables = "t_extension_items")
        private String value() { return "value"; }
    }
}
