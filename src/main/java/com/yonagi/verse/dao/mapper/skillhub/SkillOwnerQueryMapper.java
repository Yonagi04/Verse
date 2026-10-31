package com.yonagi.verse.dao.mapper.skillhub;

import com.yonagi.verse.dao.projection.SkillOwnerProjection;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 个人资源查询边界，不接受租户角色替代账号所有权。 */
public interface SkillOwnerQueryMapper {
    /**
     * @param userId 可信账号
     * @return 本人全部首版资源数
     */
    long countOwned(@Param("userId") Long userId);

    /**
     * @param userId 可信账号
     * @param size 已验证页大小
     * @param offset 已验证偏移
     * @return 本人资源一页
     */
    List<SkillOwnerProjection> selectOwnedPage(@Param("userId") Long userId,
                                              @Param("size") int size, @Param("offset") int offset);
}
