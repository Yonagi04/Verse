package com.yonagi.verse.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.regex.Pattern;

/** SQL 执行前统一失效，包含后台事件消费者与投影任务，避免遗漏写入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
public class QueryWriteInterceptor implements Interceptor {
    private static final Object RESOURCE = new Object();
    private static final Pattern TARGET = Pattern.compile("(?is)^\\s*(?:update\\s+|insert\\s+(?:ignore\\s+)?into\\s+|replace\\s+into\\s+|delete\\s+from\\s+)`?(t_[a-z0-9_]+)`?\\b");
    private static final String LAST_USED_SQL = "UPDATE t_api_key SET last_used_at = ? WHERE api_key_id = ? "
            + "AND (last_used_at IS NULL OR last_used_at < ?)";
    private final QueryCache cache;
    private final QueryCatalogue catalogue;

    static String table(String sql) {
        var matcher = TARGET.matcher(sql);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        String sql = statement.getBoundSql(invocation.getArgs()[1]).getSql();
        // 最近使用时间由 Key 列表每次批量补充，不进入结果缓存；只豁免固定 SQL。
        if (statement.getId().equals("com.yonagi.verse.dao.mapper.ApiKeyMapper.updateLastUsedAtIfLater")
                && LAST_USED_SQL.equalsIgnoreCase(sql.replaceAll("\\s+", " ").trim()))
            return invocation.proceed();
        String table = table(sql);
        if (table == null) {
            var names = Pattern.compile("(?i)\\bt_[a-z0-9_]+\\b").matcher(sql);
            while (names.find()) {
                if (catalogue.dependsOn(names.group().toLowerCase(Locale.ROOT)))
                    throw new com.yonagi.verse.common.convention.exception.ServerException("无法识别核心数据写入，已阻止 SQL 执行");
            }
        }
        if (table == null || !catalogue.dependsOn(table)) return invocation.proceed();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            Mutation state = (Mutation) TransactionSynchronizationManager.getResource(RESOURCE);
            if (state == null) {
                state = new Mutation();
                TransactionSynchronizationManager.bindResource(RESOURCE, state);
                Mutation registered = state;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void suspend() { TransactionSynchronizationManager.unbindResource(RESOURCE); }
                    @Override public void resume() { TransactionSynchronizationManager.bindResource(RESOURCE, registered); }
                    @Override public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
                        registered.tables.forEach(t -> {
                            try { cache.afterWrite(t, registered.token); }
                            catch (RuntimeException error) {
                                // 保留栅栏并告警，不能在失效失败后允许命中旧结果。
                                log.error("查询缓存写栅栏清理失败，需要安全恢复: table={}, token={}", t, registered.token, error);
                            }
                        });
                    }
                });
            }
            if (state.tables.add(table)) cache.beforeWrite(table, state.token);
            return invocation.proceed();
        }
        String token = UUID.randomUUID().toString();
        try {
            cache.beforeWrite(table, token);
            return invocation.proceed();
        } finally { cache.afterWrite(table, token); }
    }

    private static class Mutation {
        private final String token = UUID.randomUUID().toString();
        private final Set<String> tables = new LinkedHashSet<>();
    }
}
