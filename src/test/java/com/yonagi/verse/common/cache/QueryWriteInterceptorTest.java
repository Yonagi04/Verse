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
        Executor intercepted=(Executor)new QueryWriteInterceptor(cache, QueryCacheTestSupport.catalogue()).plugin(db);intercepted.update(s,null);
        var order=inOrder(cache,db);order.verify(cache).beforeWrite(eq("t_llm_service"),anyString());order.verify(db).update(s,null);order.verify(cache).afterWrite(eq("t_llm_service"),anyString());
    }
    @Test void transactionKeepsFenceUntilCompletionIncludingRollback() throws Exception {
        QueryCache cache=mock(QueryCache.class);Executor db=mock(Executor.class);Executor intercepted=(Executor)new QueryWriteInterceptor(cache, QueryCacheTestSupport.catalogue()).plugin(db);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{var s=statement("DELETE FROM t_api_key WHERE api_key_id=1");intercepted.update(s,null);intercepted.update(s,null);
            verify(cache,times(1)).beforeWrite(eq("t_api_key"),anyString());verify(cache,never()).afterWrite(anyString(),anyString());
            TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCompletion(1));verify(cache).afterWrite(eq("t_api_key"),anyString());
        }finally{TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void redisFailurePreventsDatabaseMutation(){
        QueryCache cache=mock(QueryCache.class);Executor db=mock(Executor.class);doThrow(new IllegalStateException("redis unavailable")).when(cache).beforeWrite(anyString(),anyString());
        Executor intercepted=(Executor)new QueryWriteInterceptor(cache, QueryCacheTestSupport.catalogue()).plugin(db);
        assertThrows(Exception.class,()->intercepted.update(statement("UPDATE t_tenant SET status=0"),null));verifyNoInteractions(db);
    }
    @Test void onlyFixedLastUsedStatementIsExempt() throws Exception {
        QueryCache cache = mock(QueryCache.class); Executor db = mock(Executor.class);
        Executor intercepted = (Executor) new QueryWriteInterceptor(cache, QueryCacheTestSupport.catalogue()).plugin(db);
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
        Executor intercepted = (Executor) new QueryWriteInterceptor(cache, QueryCacheTestSupport.catalogue()).plugin(db);
        assertThrows(Exception.class, () -> intercepted.update(statement("/* custom mutation */ UPDATE t_user SET status=0"), null));
        verifyNoInteractions(db);
    }

    @Test void recognizesExistingMutationForms(){
        assertEquals("t_user",QueryWriteInterceptor.table(" UPDATE t_user u SET status=0"));
        assertEquals("t_user_tenant",QueryWriteInterceptor.table("UPDATE t_user_tenant ut JOIN t_tenant t ON t.tenant_id=ut.tenant_id SET ut.favorite=1"));
        assertEquals("t_llm_service",QueryWriteInterceptor.table("INSERT INTO `t_llm_service` (name) VALUES (?)"));
        assertEquals("t_notification_recipient",QueryWriteInterceptor.table("DELETE FROM t_notification_recipient WHERE id=?"));
    }
}
