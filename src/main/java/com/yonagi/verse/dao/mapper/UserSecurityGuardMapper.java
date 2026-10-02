package com.yonagi.verse.dao.mapper;
import org.apache.ibatis.annotations.*;
import com.yonagi.verse.dao.entity.UserDO;
public interface UserSecurityGuardMapper {
    @Insert("INSERT INTO t_user_security_guard(user_id,security_version) VALUES(#{userId},0) ON DUPLICATE KEY UPDATE user_id=user_id")
    void ensure(Long userId);
    @Select("SELECT security_version FROM t_user_security_guard WHERE user_id=#{userId} FOR UPDATE")
    Long lock(Long userId);
    @Update("UPDATE t_user_security_guard SET security_version=security_version+1 WHERE user_id=#{userId}")
    void invalidate(Long userId);
    @Select("SELECT security_version FROM t_user_security_guard WHERE user_id=#{userId}")
    Long version(Long userId);
    @Select("SELECT * FROM t_user WHERE user_id=#{userId} AND del_flag=0 FOR UPDATE")
    UserDO lockUser(Long userId);
    @Insert("INSERT INTO t_user_email_quota_guard(email_hash) VALUES(#{hash}) ON DUPLICATE KEY UPDATE email_hash=email_hash")
    void ensureEmail(String hash);
    @Select("SELECT email_hash FROM t_user_email_quota_guard WHERE email_hash=#{hash} FOR UPDATE")
    String lockEmail(String hash);
    @Select("SELECT COUNT(*) FROM t_user WHERE email_hash=#{hash} AND del_flag=0 AND status<>2")
    long emailCount(String hash);
    @Insert("INSERT INTO t_user_external_auth_audit(event_id,action,provider,user_id,external_identity_id,binding_id,flow_id,operation_id,outcome) VALUES(#{event},#{action},#{provider},#{user},#{identity},#{binding},#{flow},#{operation},#{outcome})")
    void audit(String event, String action, String provider, Long user, Long identity, Long binding, String flow, String operation, String outcome);
    @Select("SELECT binding_id FROM t_user_external_auth_audit WHERE user_id=#{user} AND operation_id=#{operation} AND action='UNBIND'")
    Long unbindResult(Long user, String operation);
}
