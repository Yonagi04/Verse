package com.yonagi.verse.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** SQL 执行前统一失效，包含后台事件消费者与投影任务，避免遗漏写入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "flushStatements", args = {}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class,
                        ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "queryCursor",
                args = {MappedStatement.class, Object.class, RowBounds.class}),
        @Signature(type = Executor.class, method = "commit", args = {boolean.class}),
        @Signature(type = Executor.class, method = "rollback", args = {boolean.class}),
        @Signature(type = Executor.class, method = "close", args = {boolean.class})
})
public class QueryWriteInterceptor implements Interceptor {
    private static final Object RESOURCE = new Object();
    private static final Pattern TARGET = Pattern.compile("(?is)^\\s*(?:update\\s+"
            + "|insert\\s+(?:ignore\\s+)?into\\s+|replace\\s+into\\s+|delete\\s+from\\s+)`?(t_[a-z0-9_]+)`?\\b");
    private static final Pattern TABLE_REFERENCE = Pattern.compile("(?i)\\bt_[a-z0-9_]+\\b");
    private static final Pattern SCOPED_INSERT = Pattern.compile(
            "(?is)^\\s*(?:insert\\s+(?:ignore\\s+)?into|replace\\s+into)"
                    + "\\s+`?t_[a-z0-9_]+`?\\s*\\([^;]+?\\)\\s*values\\b");
    private static final Pattern SCOPED_UPDATE = Pattern.compile(
            "(?is)^\\s*update\\s+`?t_[a-z0-9_]+`?\\s+set\\b");
    private static final Pattern SCOPED_DELETE = Pattern.compile(
            "(?is)^\\s*delete\\s+from\\s+`?t_[a-z0-9_]+`?\\s+where\\b");
    private static final Pattern UNSAFE_SCOPED = Pattern.compile("(?is);|/\\*|--|#|\\bjoin\\b");
    private static final String LAST_USED_SQL = "UPDATE t_api_key SET last_used_at = ? WHERE api_key_id = ? "
            + "AND (last_used_at IS NULL OR last_used_at < ?)";
    private final QueryCache cache;
    private final QueryCatalogue catalogue;
    private final QueryFenceRecovery recovery;
    private final Map<Executor, Mutation> sessions = Collections.synchronizedMap(new IdentityHashMap<>());

    static String table(String sql) {
        java.util.regex.Matcher matcher = TARGET.matcher(sql);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String method = invocation.getMethod().getName();
        if (method.equals("flushStatements") || method.equals("query") || method.equals("queryCursor")) {
            return observeExecution(invocation);
        }
        if (!method.equals("update")) {
            return finishSession(invocation);
        }
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        String sql = statement.getBoundSql(invocation.getArgs()[1]).getSql();
        // 最近使用时间由 Key 列表每次批量补充，不进入结果缓存；只豁免固定 SQL。
        if (statement.getId().equals("com.yonagi.verse.dao.mapper.ApiKeyMapper.updateLastUsedAtIfLater")
                && LAST_USED_SQL.equalsIgnoreCase(sql.replaceAll("\\s+", " ").trim())) {
            return invocation.proceed();
        }
        String table = table(sql);
        guardScopedSql(sql, table, statement.getSqlCommandType());
        if (table == null) {
            java.util.regex.Matcher names = TABLE_REFERENCE.matcher(sql);
            while (names.find()) {
                if (catalogue.dependsOn(names.group().toLowerCase(Locale.ROOT))) {
                    throw new com.yonagi.verse.common.convention.exception.ServerException(
                            "无法识别核心数据写入，已阻止 SQL 执行");
                }
            }
        }
        if (table == null || !catalogue.dependsOn(table)) {
            return invocation.proceed();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            Mutation state = (Mutation) TransactionSynchronizationManager.getResource(RESOURCE);
            if (state == null) {
                state = new Mutation();
                TransactionSynchronizationManager.bindResource(RESOURCE, state);
                Mutation registered = state;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void suspend() { TransactionSynchronizationManager.unbindResource(RESOURCE); }
                    @Override public void resume() {
                        TransactionSynchronizationManager.bindResource(RESOURCE, registered);
                    }
                    @Override public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
                        if (status == STATUS_COMMITTED || status == STATUS_ROLLED_BACK) {
                            completed(registered);
                        } else {
                            registered.dependencies.forEach(t -> unknown(t, registered.token));
                        }
                    }
                });
            }
            begin(state, catalogue.mutationTargets(statement.getId(), table, invocation.getArgs()[1]));
            return execute(invocation, state);
        }
        // update 返回可能仅表示批处理已入队；SqlSessionTemplate 随后强制 commit 才是结束边界。
        Executor executor = (Executor) invocation.getTarget();
        Mutation state = sessions.computeIfAbsent(executor, ignored -> new Mutation());
        begin(state, catalogue.mutationTargets(statement.getId(), table, invocation.getArgs()[1]));
        return execute(invocation, state);
    }

    private void guardScopedSql(String sql, String target, SqlCommandType command) {
        java.util.regex.Matcher references = TABLE_REFERENCE.matcher(sql);
        boolean protectedReference = false;
        while (references.find()) {
            if (catalogue.scopeEnabled(references.group().toLowerCase(Locale.ROOT))) {
                protectedReference = true;
            }
        }
        if (!protectedReference) {
            return;
        }
        boolean supported = target != null && catalogue.scopeEnabled(target)
                && !UNSAFE_SCOPED.matcher(sql).find() && switch (command) {
                    case INSERT -> SCOPED_INSERT.matcher(sql).find();
                    case UPDATE -> SCOPED_UPDATE.matcher(sql).find();
                    case DELETE -> SCOPED_DELETE.matcher(sql).find();
                    default -> false;
                };
        if (!supported) {
            throw new com.yonagi.verse.common.convention.exception.ServerException(
                    "作用域缓存不支持此写入语法，已阻止 SQL 执行");
        }
    }

    private void begin(Mutation state, List<QueryDependency> dependencies) {
        Set<QueryDependency> combined = new HashSet<>(state.dependencies);
        combined.addAll(dependencies);
        if (combined.stream().anyMatch(dependency -> !dependency.isTable())
                && combined.size() > catalogue.maxDependencies()) {
            throw new com.yonagi.verse.common.convention.exception.ServerException(
                    "事务累计缓存依赖超过上限，已阻止 SQL 执行");
        }
        state.dependencies.addAll(dependencies);
        for (QueryDependency dependency : dependencies) {
            if (!state.prepared.contains(dependency)) {
                if (dependency.isTable()) {
                    cache.beforeWrite(dependency.table(), state.token);
                } else {
                    cache.beforeWrite(dependency, state.token);
                }
                state.prepared.add(dependency);
            }
        }
    }

    private Object observeExecution(Invocation invocation) throws Throwable {
        Mutation state = sessions.get((Executor) invocation.getTarget());
        if (state == null && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            state = (Mutation) TransactionSynchronizationManager.getResource(RESOURCE);
        }
        // BATCH 查询内部的 flush 是自调用，必须在外层读取入口观测失败；只读会话不创建写状态。
        return state == null ? invocation.proceed() : execute(invocation, state);
    }

    private Object execute(Invocation invocation, Mutation state) throws Throwable {
        try { return invocation.proceed(); }
        catch (Exception | Error error) { state.uncertain = true; throw error; }
    }

    private Object finishSession(Invocation invocation) throws Throwable {
        Executor executor = (Executor) invocation.getTarget();
        Mutation state = sessions.get(executor);
        if (state == null) {
            return invocation.proceed();
        }
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
                if (ended) {
                    completed(state);
                } else {
                    state.dependencies.forEach(t -> unknown(t, state.token));
                }
            }
        }
        Object result;
        try { result = invocation.proceed(); }
        catch (Exception | Error error) { state.uncertain = true; throw error; }
        // false 只保证 flush/local-cache 清理，不保证数据库事务已结束。
        if (Boolean.TRUE.equals(invocation.getArgs()[0]) && confirmsEnd(executor, state)) {
            sessions.remove(executor);
            completed(state);
        }
        return result;
    }

    private boolean confirmsEnd(Executor executor, Mutation state) throws java.sql.SQLException {
        // 自动提交时 MyBatis 的 commit/rollback 是空操作，不能证明超时 SQL 已在服务端结束。
        if (!state.uncertain) {
            return true;
        }
        org.apache.ibatis.transaction.Transaction transaction = executor.getTransaction();
        return transaction != null && !transaction.getConnection().getAutoCommit();
    }

    private void completed(Mutation state) {
        state.dependencies.forEach(dependency -> {
            if (dependency.isTable()) {
                recovery.completed(dependency.table(), state.token);
            } else {
                recovery.completed(dependency, state.token);
            }
        });
    }

    private void unknown(QueryDependency dependency, String token) {
        if (dependency.isTable()) {
            recovery.unknown(dependency.table(), token);
        } else {
            recovery.unknown(dependency, token);
        }
    }

    private static class Mutation {
        private final String token = UUID.randomUUID().toString();
        private final Set<QueryDependency> dependencies = new LinkedHashSet<>();
        private final Set<QueryDependency> prepared = new HashSet<>();
        private boolean uncertain;
    }
}
