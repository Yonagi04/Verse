package com.yonagi.verse.common.cache;

import com.yonagi.verse.service.apikey.ApiKeyListCacheBehavior;
import com.yonagi.verse.service.cache.HourlyCacheBehavior;
import com.yonagi.verse.service.playground.WorkbenchListCacheBehavior;
import com.yonagi.verse.service.playground.WorkbenchModelsCacheBehavior;
import com.yonagi.verse.service.reporting.UsageReportWindowCacheBehavior;
import com.yonagi.verse.service.tenant.TenantInvitesCacheBehavior;
import com.yonagi.verse.service.tenant.TenantOverviewCacheBehavior;

import com.yonagi.verse.service.cache.LiveModelBindingCacheBehavior;
import com.yonagi.verse.service.cache.LiveModelRouteCacheBehavior;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/** 独立测试使用与启动发现相同的注解元数据，不创建业务服务。 */
final class QueryCacheTestSupport {
    private QueryCacheTestSupport() { }

    static QueryCatalogue catalogue() {
        QueryCatalogue catalogue = new QueryCatalogue();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        for (var bean : scanner.findCandidateComponents("com.yonagi.verse.service")) {
            try { catalogue.register(Class.forName(bean.getBeanClassName())); }
            catch (ClassNotFoundException error) { throw new AssertionError(error); }
        }
        return catalogue;
    }

    static QueryCatalogue.Policy policy(String owner, String method) {
        return catalogue().policies().values().stream()
                .filter(policy -> policy.name().equals(owner + "." + method)).findFirst().orElseThrow();
    }

    static CoreQueryCacheAspect aspect(QueryCache cache, QueryAccessPolicy... policies) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("hourly", new HourlyCacheBehavior());
        beans.registerSingleton("overview", new TenantOverviewCacheBehavior());
        beans.registerSingleton("reportWindow", new UsageReportWindowCacheBehavior());
        beans.registerSingleton("workbenchModels", new WorkbenchModelsCacheBehavior());
        beans.registerSingleton("workbenchList", new WorkbenchListCacheBehavior());

        beans.registerSingleton("invites", new TenantInvitesCacheBehavior());
        beans.registerSingleton("apiKeys", new ApiKeyListCacheBehavior(cache));
        beans.registerSingleton("liveModelRoute", new LiveModelRouteCacheBehavior());
        beans.registerSingleton("liveModelBinding", new LiveModelBindingCacheBehavior());
        for (Class<? extends QueryAccessPolicy> type : catalogue().policies().values().stream().map(QueryCatalogue.Policy::access).distinct().toList()) {
            if (type == NoQueryAccess.class) continue;
            QueryAccessPolicy policy = java.util.Arrays.stream(policies).filter(type::isInstance).findFirst().orElseGet(() -> org.mockito.Mockito.mock(type));
            beans.registerSingleton("access-" + type.getName(), policy);
        }
        return new CoreQueryCacheAspect(cache, beans);
    }
}
