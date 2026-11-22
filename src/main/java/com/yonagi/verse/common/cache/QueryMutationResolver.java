package com.yonagi.verse.common.cache;

import java.util.List;

/** 无状态的写归属解析；SQL 必须显式绑定相同所有者，不能从 Wrapper 或操作者猜测。 */
public interface QueryMutationResolver {
    /**
     * @param statementId 已在 QueryMutationBinding 中登记的完整写语句标识
     * @param table 当前 SQL 唯一目标表
     * @param parameter Mapper 显式归属参数
     * @return 当前表的非空影响范围；TABLE 表示广播，不得返回异表或内部广播资源
     */
    List<QueryDependency> resolve(String statementId, String table, Object parameter);
}
