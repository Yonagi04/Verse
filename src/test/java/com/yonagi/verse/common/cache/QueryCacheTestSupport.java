package com.yonagi.verse.common.cache;

import com.yonagi.verse.service.cache.QueryCacheBehaviors;
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

    static CoreQueryCacheAspect aspect(QueryCache cache, QueryAccessGuard guard) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("hourly", new QueryCacheBehaviors.Hourly());
        beans.registerSingleton("overview", new QueryCacheBehaviors.Overview());
        beans.registerSingleton("reportWindow", new QueryCacheBehaviors.ReportWindow());
        beans.registerSingleton("workbenchModels", new QueryCacheBehaviors.WorkbenchModels());
        beans.registerSingleton("workbenchList", new QueryCacheBehaviors.WorkbenchList());
        beans.registerSingleton("workbenchDetail", new QueryCacheBehaviors.WorkbenchDetail(guard));
        beans.registerSingleton("invites", new QueryCacheBehaviors.Invites());
        beans.registerSingleton("apiKeys", new QueryCacheBehaviors.ApiKeys(cache));
        beans.registerSingleton("liveModelRoute", new LiveModelRouteCacheBehavior());
        beans.registerSingleton("liveModelBinding", new LiveModelBindingCacheBehavior());
        return new CoreQueryCacheAspect(cache, guard, beans);
    }
}
