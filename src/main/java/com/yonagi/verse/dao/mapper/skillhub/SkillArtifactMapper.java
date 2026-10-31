package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 不可变私有制品的索引、摘要和存储生命周期的单表持久化访问。 */
public interface SkillArtifactMapper extends BaseMapper<SkillArtifactDO> {
    /**
     * @param artifactId 已验证资源引用的快照
     * @return 持行锁的制品，不存在时null；新增绑定与清理共用此锁
     */
    @Select("""
            SELECT artifact_id,owner_user_id,archive_key,archive_sha256,content_sha256,
                   archive_bytes,unpacked_bytes,file_count,skill_markdown,manifest_json,status,create_time,update_time
            FROM t_skill_artifact WHERE artifact_id=#{artifactId} FOR UPDATE
            """)
    SkillArtifactDO selectByIdForUpdate(@Param("artifactId") Long artifactId);
}
