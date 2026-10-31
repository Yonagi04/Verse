package com.yonagi.verse.dao.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账号领域允许的只读可用性契约；Skill公开分页采用同一ACTIVE/未删除谓词。 */
public interface AccountAvailabilityMapper {
    /**
     * @param userId 可信账号，不读取邮箱、手机号或其他隐私字段
     * @return 有效账号的现有昵称，空昵称退到用户名；账号不可用时null
     */
    @Select("""
            SELECT COALESCE(NULLIF(TRIM(nickname),''),username)
            FROM t_user WHERE user_id=#{userId} AND status=1 AND del_flag=0
            """)
    String selectActiveDisplayName(@Param("userId") Long userId);

    /**
     * 查询账号是否正常且未删除，不读取账号隐私或租户资料。
     *
     * @param userId 待检查的账号标识
     * @return 满足条件的账号标识；不存在或已停用时返回null
     */
    @Select("SELECT user_id FROM t_user WHERE user_id=#{userId} AND status=1 AND del_flag=0")
    Long selectActiveUserId(@Param("userId") Long userId);

    /**
     * 在调用者事务中持有账号共享锁，必须先于Skill资源锁取得，事务结束时释放。
     *
     * @param userId 待检查并保护的账号标识
     * @return 当前读确认正常且未删除的账号标识；不满足条件时返回null
     */
    @Select("SELECT user_id FROM t_user WHERE user_id=#{userId} AND status=1 AND del_flag=0 FOR SHARE")
    Long selectActiveUserIdForShare(@Param("userId") Long userId);

    /**
     * @param userId 正在首次建立发布关联的账号
     * @return 正常未删除账号标识并持排他锁，否则null；锁随调用者事务结束
     */
    @Select("SELECT user_id FROM t_user WHERE user_id=#{userId} AND status=1 AND del_flag=0 FOR UPDATE")
    Long selectActiveUserIdForUpdate(@Param("userId") Long userId);
}
