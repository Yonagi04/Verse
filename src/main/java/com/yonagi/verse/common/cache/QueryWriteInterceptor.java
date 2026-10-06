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
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "commit", args = {boolean.class}),
        @Signature(type = Executor.class, method = "rollback", args = {boolean.class}),
        @Signature(type = Executor.class, method = "close", args = {boolean.class})
})
public class QueryWriteInterceptor implements Interceptor {
    private static final Object RESOURCE = new Object();
    private static final Pattern TARGET = Pattern.compile("(?is)^\\s*(?:update\\s+|insert\\s+(?:ignore\\s+)?into\\s+|replace\\s+into\\s+|delete\\s+from\\s+)`?(t_[a-z0-9_]+)`?\\b");
    private static final String LAST_USED_SQL = "UPDATE t_api_key SET last_used_at = ? WHERE api_key_id = ? "
            + "AND (last_used_at IS NULL OR last_used_at < ?)";
    private final QueryCache cache;
    private final QueryCatalogue catalogue;
    private final QueryFenceRecovery recovery;
    private final Map<Executor, Mutation> sessions = Collections.synchronizedMap(new IdentityHashMap<>());

    static String table(String sql) {
        var matcher = TARGET.matcher(sql);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!invocation.getMethod().getName().equals("update")) return finishSession(invocation);
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
                        if (status == STATUS_COMMITTED || status == STATUS_ROLLED_BACK) completed(registered);
                        else registered.tables.forEach(t -> recovery.unknown(t, registered.token));
                    }
                });
            }
            begin(state, table);
            return update(invocation, state);
        }
        // update 返回可能仅表示批处理已入队；SqlSessionTemplate 随后强制 commit 才是结束边界。
        Executor executor = (Executor) invocation.getTarget();
        Mutation state = sessions.computeIfAbsent(executor, ignored -> new Mutation());
        begin(state, table);
        return update(invocation, state);
    }

    private void begin(Mutation state, String table) {
        state.tables.add(table);
        if (!state.prepared.contains(table)) {
            cache.beforeWrite(table, state.token);
            state.prepared.add(table);
        }
    }

    private Object update(Invocation invocation, Mutation state) throws Throwable {
        try { return invocation.proceed(); }
        catch (Throwable error) { state.uncertain = true; throw error; }
    }

    private Object finishSession(Invocation invocation) throws Throwable {
        Executor executor = (Executor) invocation.getTarget();
        Mutation state = sessions.get(executor);
        if (state == null) return invocation.proceed();
        String method = invocation.getMethod().getName();
        if (method.equals("close")) {
            sessions.remove(executor);
            // BaseExecutor.close 会吞掉内部 rollback 的 SQLException，不能用 close 返回作为证明。
            boolean ended = false;
            try {
                boolean confirms = confirmsEnd(executor, state);
                executor.rollback(true);
                ended = confirms;
            }
            catch (java.sql.SQLException | RuntimeException error) {
                log.warn("[query-cache] 会话关闭前回滚失败，保留未确认栅栏", error);
            }
            try { return invocation.proceed(); }
            finally {
                if (ended) completed(state);
                else state.tables.forEach(t -> recovery.unknown(t, state.token));
            }
        }
        Object result;
        try { result = invocation.proceed(); }
        catch (Throwable error) { state.uncertain = true; throw error; }
        // false 只保证 flush/local-cache 清理，不保证数据库事务已结束。
        if (Boolean.TRUE.equals(invocation.getArgs()[0]) && confirmsEnd(executor, state)) {
            sessions.remove(executor);
            completed(state);
        }
        return result;
    }

    private boolean confirmsEnd(Executor executor, Mutation state) throws java.sql.SQLException {
        // 自动提交时 MyBatis 的 commit/rollback 是空操作，不能证明超时 SQL 已在服务端结束。
        if (!state.uncertain) return true;
        var transaction = executor.getTransaction();
        return transaction != null && !transaction.getConnection().getAutoCommit();
    }

    private void completed(Mutation state) {
        state.tables.forEach(t -> recovery.completed(t, state.token));
    }

    private static class Mutation {
        private final String token = UUID.randomUUID().toString();
        private final Set<String> tables = new LinkedHashSet<>();
        private final Set<String> prepared = new HashSet<>();
        private boolean uncertain;
    }
}
