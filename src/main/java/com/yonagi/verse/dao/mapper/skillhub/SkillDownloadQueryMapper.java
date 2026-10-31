package com.yonagi.verse.dao.mapper.skillhub;

import com.yonagi.verse.dao.projection.SkillDownloadProjection;
import org.apache.ibatis.annotations.Param;

/** 下载实时可用性与最终当前读，账号锁由跨领域公开服务先获取。 */
public interface SkillDownloadQueryMapper {
    /**
     * @param skillId 公开Skill
     * @param versionId 固定版本
     * @return 可用私有资源；不可用为null
     */
    SkillDownloadProjection selectAvailable(@Param("skillId") Long skillId, @Param("versionId") Long versionId);

    /**
     * @param versionId 仅服务端grant绑定版本
     * @return 已公开版本所属账号，用于按统一锁序查账号；无则null
     */
    Long selectOwnerId(@Param("versionId") Long versionId);

}
