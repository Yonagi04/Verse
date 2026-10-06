package com.yonagi.verse.common.cache;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.jdbc.JdbcTransaction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用实际 BATCH、JdbcTransaction 和缓存装饰器，验证 JDBC 执行证据而非模拟 Executor 结果。 */
class QueryWriteBatchFailureTest {
    enum FlushPath { EXPLICIT, QUERY, QUERY_WITH_CACHE_KEY, CURSOR }

    static Stream<Object[]> failedFlushCases() {
        return Stream.of(false, true).flatMap(autoCommit -> Stream.of(false, true).flatMap(caching ->
                Stream.of(FlushPath.values()).map(path -> new Object[]{autoCommit, caching, path})));
    }

    @ParameterizedTest
    @MethodSource("failedFlushCases")
    void flushTimeoutRequiresRealTransactionCompletion(boolean autoCommit, boolean caching, FlushPath path) throws Exception {
        Fixture fixture = new Fixture(autoCommit, caching);
        when(fixture.statement.executeBatch()).thenThrow(new SQLTimeoutException("batch outcome unknown"));
        fixture.executor.update(fixture.write, null);
        assertThrows(SQLException.class, () -> fixture.flush(path));
        verify(fixture.statement).executeBatch();
        // 即使调用方在异常后继续强制提交/回滚，自动提交连接上的空操作也不能证明结束。
        if (autoCommit) {
            fixture.executor.commit(true);
            fixture.executor.rollback(true);
        }
        fixture.executor.close(false);
        verify(fixture.connection, autoCommit ? never() : times(1)).rollback();
        if (autoCommit) verify(fixture.cache, never()).afterWrite(anyString(), anyString());
        else verify(fixture.cache).afterWrite(eq("t_tenant"), anyString());
    }

    static Stream<Object[]> successfulFlushCases() {
        return Stream.of(false, true).flatMap(autoCommit -> Stream.of(false, true)
                .map(caching -> new Object[]{autoCommit, caching}));
    }

    @ParameterizedTest
    @MethodSource("successfulFlushCases")
    void successfulBatchStillAllowsCompletion(boolean autoCommit, boolean caching) throws Exception {
        Fixture fixture = new Fixture(autoCommit, caching);
        when(fixture.statement.executeBatch()).thenReturn(new int[]{1});
        fixture.executor.update(fixture.write, null);
        fixture.executor.flushStatements();
        fixture.executor.close(false);
        verify(fixture.statement).executeBatch();
        verify(fixture.cache).afterWrite(eq("t_tenant"), anyString());
    }

    private static class Fixture {
        private final QueryCache cache = mock(QueryCache.class);
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final Executor executor;
        private final MappedStatement write;
        private final MappedStatement read;

        Fixture(boolean autoCommit, boolean caching) throws Exception {
            when(connection.getAutoCommit()).thenReturn(autoCommit);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            Configuration configuration = new Configuration();
            configuration.setCacheEnabled(caching);
            configuration.addInterceptor(QueryCacheTestSupport.interceptor(cache));
            write = new MappedStatement.Builder(configuration, "test.write",
                    new StaticSqlSource(configuration, "UPDATE t_tenant SET status=0"), SqlCommandType.UPDATE).build();
            read = new MappedStatement.Builder(configuration, "test.read",
                    new StaticSqlSource(configuration, "SELECT status FROM t_tenant"), SqlCommandType.SELECT)
                    .resultMaps(List.of(new ResultMap.Builder(configuration, "test.status", Integer.class, List.of()).build()))
                    .build();
            executor = configuration.newExecutor(new JdbcTransaction(connection), ExecutorType.BATCH);
        }

        void flush(FlushPath path) throws SQLException {
            switch (path) {
                case EXPLICIT -> executor.flushStatements();
                case QUERY -> executor.query(read, null, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
                case QUERY_WITH_CACHE_KEY -> {
                    var sql = read.getBoundSql(null);
                    var key = executor.createCacheKey(read, null, RowBounds.DEFAULT, sql);
                    executor.query(read, null, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, key, sql);
                }
                case CURSOR -> executor.queryCursor(read, null, RowBounds.DEFAULT);
            }
        }
    }
}
