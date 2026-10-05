package com.yonagi.verse.common.cache;

import com.yonagi.verse.support.MySqlTestDatabase;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.forward.*;
import com.yonagi.verse.service.forward.impl.ModelResolverImpl;
import com.yonagi.verse.service.impl.LlmForwardServiceImpl;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.budget.CostBudgetService;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.usage.UsageNormalizerRegistry;
import com.yonagi.verse.resilience.api.*;
import com.yonagi.verse.resilience.impl.Resilience4jTimeLimiter;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.api.TokenUsageEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用真实适配器注册与强制 CGLIB 的容器启动，防止缓存代理破坏依赖装配。 */
class CoreQueryCacheStartupTest {
    private final ApplicationContextRunner dependencies = new ApplicationContextRunner()
            .withUserConfiguration(StartupConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(AesUtil.class, () -> mock(AesUtil.class))
            .withBean(DomainEventPublisher.class, () -> mock(DomainEventPublisher.class))
            .withBean(TokenUsageEventPublisher.class, () -> mock(TokenUsageEventPublisher.class))
            .withBean(CostBudgetService.class, () -> mock(CostBudgetService.class))
            .withBean(RateLimiter.class, () -> mock(RateLimiter.class))
            .withBean(CircuitBreaker.class, () -> mock(CircuitBreaker.class))
            .withBean(FallbackExecutor.class, () -> mock(FallbackExecutor.class));

    private final ApplicationContextRunner context = dependencies
            .withBean(QueryCache.class, () -> mock(QueryCache.class))
            .withBean(QueryAccessGuard.class, () -> mock(QueryAccessGuard.class))
            .withBean(LlmServiceMapper.class, () -> mock(LlmServiceMapper.class))
            .withBean(LlmServiceCapabilityMapper.class, () -> mock(LlmServiceCapabilityMapper.class))
            .withBean(TenantMapper.class, () -> mock(TenantMapper.class))
            .withBean(LlmServicePricingMapper.class, () -> mock(LlmServicePricingMapper.class))
            .withBean(LlmPricingPeakPeriodMapper.class, () -> mock(LlmPricingPeakPeriodMapper.class));

    @Test void modelResolverAndFinalNativeAdaptersCanStartWithCacheEnabled() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(ModelResolver.class);
            assertThat(application).hasSingleBean(LlmForwardService.class);
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(application.getBean(ModelResolver.class))).isTrue();
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(application.getBean("anthropicChatAdapter"))).isFalse();
            assertThat(application.getBean("anthropicChatAdapter")).isInstanceOf(NativeChatAdapters.Anthropic.class);
            assertThat(application.getBean("ollamaEmbeddingAdapter")).isInstanceOf(NativeEmbeddingAdapters.Ollama.class);
        });
    }

    @Test void realCacheAndMybatisInfrastructureHaveNoStartupDependencyCycle() {
        dependencies.withUserConfiguration(PersistenceConfiguration.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class))
                .withBean(org.redisson.api.RedissonClient.class, () -> mock(org.redisson.api.RedissonClient.class))
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        () -> new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).hasSingleBean(LlmForwardService.class);
                    assertThat(application).hasSingleBean(QueryCache.class);
                    assertThat(application).hasSingleBean(QueryAccessGuard.class);
                    assertThat(application.getBean(org.apache.ibatis.session.SqlSessionFactory.class)
                            .getConfiguration().getInterceptors()).anyMatch(QueryWriteInterceptor.class::isInstance);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @org.mybatis.spring.annotation.MapperScan("com.yonagi.verse.dao.mapper")
    @Import({QueryCache.class, QueryCacheProperties.class, QueryAccessGuard.class, QueryWriteInterceptor.class})
    static class PersistenceConfiguration {
        @Bean(destroyMethod = "close")
        MySqlTestDatabase database() {
            return MySqlTestDatabase.create();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @ComponentScan(basePackageClasses = com.yonagi.verse.service.cache.QueryCacheBehaviors.class)
    @Import({QueryCatalogue.class, CoreQueryCacheAspect.class, ModelResolverImpl.class, AdapterRegistry.class,
            NativeChatAdapters.Registrations.class, NativeEmbeddingAdapters.Registrations.class,
            RoutingProviderAdapter.class, LlmForwardServiceImpl.class, PricingResolver.class,
            CostCalculator.class, UsageNormalizerRegistry.class, Resilience4jTimeLimiter.class})
    static class StartupConfiguration { }
}
