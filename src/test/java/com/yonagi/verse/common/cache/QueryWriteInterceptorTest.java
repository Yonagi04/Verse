package com.yonagi.verse.common.cache;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.builder.StaticSqlSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryWriteInterceptorTest {
    private MappedStatement statement(String sql){Configuration c=new Configuration();return new MappedStatement.Builder(c,"test.update",new StaticSqlSource(c,sql),SqlCommandType.UPDATE).build();}
    @Test void invalidationPrecedesSqlAndNonTransactionalCompletion() throws Exception {
        QueryCache cache=mock(QueryCache.class);Executor db=mock(Executor.class);var s=statement("UPDATE t_llm_service SET status=0 WHERE service_id=1");
        Executor intercepted=(Executor)QueryCacheTestSupport.interceptor(cache).plugin(db);intercepted.update(s,null);intercepted.commit(true);
        var order=inOrder(cache,db);order.verify(cache).beforeWrite(eq("t_llm_service"),anyString());order.verify(db).update(s,null);order.verify(cache).afterWrite(eq("t_llm_service"),anyString());
    }
    @Test void transactionKeepsFenceUntilCompletionIncludingRollback() throws Exception {
        QueryCache cache=mock(QueryCache.class);Executor db=mock(Executor.class);Executor intercepted=(Executor)QueryCacheTestSupport.interceptor(cache).plugin(db);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{var s=statement("DELETE FROM t_api_key WHERE api_key_id=1");intercepted.update(s,null);intercepted.update(s,null);
            verify(cache,times(1)).beforeWrite(eq("t_api_key"),anyString());verify(cache,never()).afterWrite(anyString(),anyString());
            TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCompletion(1));verify(cache).afterWrite(eq("t_api_key"),anyString());
        }finally{TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void redisFailurePreventsDatabaseMutation(){
        QueryCache cache=mock(QueryCache.class);Executor db=mock(Executor.class);doThrow(new IllegalStateException("redis unavailable")).when(cache).beforeWrite(anyString(),anyString());
        Executor intercepted=(Executor)QueryCacheTestSupport.interceptor(cache).plugin(db);
        assertThrows(Exception.class,()->intercepted.update(statement("UPDATE t_tenant SET status=0"),null));verifyNoInteractions(db);
    }
    @Test void onlyFixedLastUsedStatementIsExempt() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        Configuration c = new Configuration();
        var s = new MappedStatement.Builder(c, "com.yonagi.verse.dao.mapper.ApiKeyMapper.updateLastUsedAtIfLater",
                new StaticSqlSource(c, "UPDATE t_api_key SET last_used_at = ? WHERE api_key_id = ? AND (last_used_at IS NULL OR last_used_at < ?)"), SqlCommandType.UPDATE).build();
        intercepted.update(s, null);
        verifyNoInteractions(cache); verify(db).update(s, null);
        var changed = new MappedStatement.Builder(c, "com.yonagi.verse.dao.mapper.ApiKeyMapper.updateLastUsedAtIfLater",
                new StaticSqlSource(c, "UPDATE t_api_key SET status=0"), SqlCommandType.UPDATE).build();
        intercepted.update(changed, null);
        verify(cache).beforeWrite(eq("t_api_key"), anyString());
    }

    @Test void unsupportedCoreMutationFailsClosed() {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        assertThrows(Exception.class, () -> intercepted.update(statement("/* custom mutation */ UPDATE t_user SET status=0"), null));
        verifyNoInteractions(db);
    }

    @Test void recognizesExistingMutationForms(){
        assertEquals("t_user",QueryWriteInterceptor.table(" UPDATE t_user u SET status=0"));
        assertEquals("t_user_tenant",QueryWriteInterceptor.table("UPDATE t_user_tenant ut JOIN t_tenant t ON t.tenant_id=ut.tenant_id SET ut.favorite=1"));
        assertEquals("t_llm_service",QueryWriteInterceptor.table("INSERT INTO `t_llm_service` (name) VALUES (?)"));
        assertEquals("t_notification_recipient",QueryWriteInterceptor.table("DELETE FROM t_notification_recipient WHERE id=?"));
    }
    @Test void unknownTransactionCompletionRetainsFence() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            intercepted.update(statement("UPDATE t_tenant SET status=0"), null);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(2));
            verify(cache, never()).afterWrite(anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
    @Test void nonSpringSessionRetainsFenceUntilForcedCommitCompletes() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        intercepted.update(statement("UPDATE t_tenant SET status=0"), null);
        verify(cache, never()).afterWrite(anyString(), anyString());
        intercepted.commit(false);
        verify(cache, never()).afterWrite(anyString(), anyString());
        intercepted.commit(true);
        verify(cache).afterWrite(eq("t_tenant"), anyString());
    }
    @Test void failedCommitAndFailedCloseRollbackRetainFence() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        intercepted.update(statement("UPDATE t_tenant SET status=0"), null);
        doThrow(new java.sql.SQLException("commit outcome unknown")).when(db).commit(true);
        doThrow(new java.sql.SQLException("rollback not confirmed")).when(db).rollback(true);
        assertThrows(java.sql.SQLException.class, () -> intercepted.commit(true));
        intercepted.close(false);
        verify(cache, never()).afterWrite(anyString(), anyString());
        verify(db).close(false);
    }
    @Test void closePerformsExplicitRollbackBeforeMarkingCompletion() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        intercepted.update(statement("UPDATE t_tenant SET status=0"), null);
        intercepted.close(false);
        var order = inOrder(db, cache);
        order.verify(db).rollback(true); order.verify(db).close(false);
        order.verify(cache).afterWrite(eq("t_tenant"), anyString());
    }
    @Test void caughtFenceFailureCannotSkipPreparationOnNextWrite() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        doThrow(new IllegalStateException("begin failed")).when(cache).beforeWrite(anyString(), anyString());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            for (int i = 0; i < 2; i++)
                assertThrows(IllegalStateException.class, () -> intercepted.update(statement("UPDATE t_tenant SET status=0"), null));
            verify(cache, times(2)).beforeWrite(eq("t_tenant"), anyString());
            verifyNoInteractions(db);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void timedOutSqlRequiresActualRollbackInsteadOfAutocommitNoop(boolean autoCommit) throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        var transaction = mock(org.apache.ibatis.transaction.Transaction.class);
        var connection = mock(java.sql.Connection.class);
        when(db.getTransaction()).thenReturn(transaction); when(transaction.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(autoCommit);
        doThrow(new java.sql.SQLException("SQL timeout outcome unknown")).when(db).update(any(), any());
        Executor intercepted = (Executor) QueryCacheTestSupport.interceptor(cache).plugin(db);
        assertThrows(java.sql.SQLException.class, () -> intercepted.update(statement("UPDATE t_tenant SET status=0"), null));
        intercepted.close(false);
        verify(db).rollback(true);
        if (autoCommit) verify(cache, never()).afterWrite(anyString(), anyString());
        else verify(cache).afterWrite(eq("t_tenant"), anyString());
    }
}
