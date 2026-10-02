package com.yonagi.verse.dao.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.ExternalIdentityDO;
import org.apache.ibatis.annotations.*;
public interface ExternalIdentityMapper extends BaseMapper<ExternalIdentityDO> {
    @Select("SELECT * FROM t_external_identity WHERE id=#{id} FOR UPDATE")
    ExternalIdentityDO lock(Long id);
    @Insert("INSERT INTO t_external_identity (id,provider,issuer,subject,binding_version,email_verified) VALUES (#{id},#{provider},#{issuer},#{subject},0,0) ON DUPLICATE KEY UPDATE id=id")
    void ensure(ExternalIdentityDO identity);
}
