package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 个人收藏单表存取，所有业务入口显式限定账号。 */
public interface SkillFavoriteMapper extends BaseMapper<SkillFavoriteDO> {
    /**
     * @param userId 关系所有者，同时用于 SQL 与缓存失效
     * @param row 已生成标识和时间的收藏，不从其中读取 SQL 归属
     * @return 实际插入行数
     */
    @Insert("""
            INSERT INTO t_skill_favorite
                (favorite_id,user_id,skill_id,folder_id,saved_at,create_time,update_time)
            VALUES (#{row.favoriteId},#{userId},#{row.skillId},#{row.folderId},
                #{row.savedAt},#{row.createTime},#{row.updateTime})
            """)
    int insertOwned(@Param("userId") Long userId, @Param("row") SkillFavoriteDO row);

    /**
     * @param userId 关系所有者
     * @param favoriteId 本人收藏标识
     * @param folderId 已由业务校验归属的目标夹
     * @param updateTime 本次修改时间，保留原收藏时间
     * @return 实际更新行数
     */
    @Update("""
            UPDATE t_skill_favorite SET folder_id=#{folderId},update_time=#{updateTime}
            WHERE user_id=#{userId} AND favorite_id=#{favoriteId}
            """)
    int moveOwned(@Param("userId") Long userId, @Param("favoriteId") Long favoriteId,
                  @Param("folderId") Long folderId, @Param("updateTime") LocalDateTime updateTime);

    /**
     * @param userId 夹及收藏的共同所有者
     * @param folderId 即将删除的本人自定义夹
     * @param defaultFolderId 由业务规则提供的逻辑默认夹，保留原收藏时间
     * @param updateTime 本次修改时间
     * @return 实际迁移行数
     */
    @Update("""
            UPDATE t_skill_favorite SET folder_id=#{defaultFolderId},update_time=#{updateTime}
            WHERE user_id=#{userId} AND folder_id=#{folderId}
            """)
    int moveFolderToDefault(@Param("userId") Long userId, @Param("folderId") Long folderId,
                            @Param("defaultFolderId") Long defaultFolderId,
                            @Param("updateTime") LocalDateTime updateTime);

    /**
     * @param userId 关系所有者
     * @param favoriteId 本人收藏标识
     * @return 实际删除行数
     */
    @Delete("DELETE FROM t_skill_favorite WHERE user_id=#{userId} AND favorite_id=#{favoriteId}")
    int deleteOwned(@Param("userId") Long userId, @Param("favoriteId") Long favoriteId);

    /**
     * @param userId 注销或补偿清理的账号，不要求账号仍正常
     * @return 实际删除行数
     */
    @Delete("DELETE FROM t_skill_favorite WHERE user_id=#{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
