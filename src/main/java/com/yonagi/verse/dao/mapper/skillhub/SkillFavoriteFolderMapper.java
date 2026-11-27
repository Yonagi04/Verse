package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillFavoriteFolderDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 个人收藏单表存取，所有业务入口显式限定账号。 */
public interface SkillFavoriteFolderMapper extends BaseMapper<SkillFavoriteFolderDO> {
    /**
     * @param userId 夹所有者，同时用于 SQL 与缓存失效
     * @param row 已生成标识、规范名称和时间的夹，不从其中读取 SQL 归属
     * @return 实际插入行数
     */
    @Insert("""
            INSERT INTO t_skill_favorite_folder (folder_id,user_id,name,name_key,create_time,update_time)
            VALUES (#{row.folderId},#{userId},#{row.name},#{row.nameKey},#{row.createTime},#{row.updateTime})
            """)
    int insertOwned(@Param("userId") Long userId, @Param("row") SkillFavoriteFolderDO row);

    /**
     * @param userId 夹所有者
     * @param folderId 本人自定义夹标识
     * @param name 规范展示名称
     * @param nameKey 规范唯一名称键
     * @param updateTime 本次修改时间
     * @return 实际更新行数
     */
    @Update("""
            UPDATE t_skill_favorite_folder SET name=#{name},name_key=#{nameKey},update_time=#{updateTime}
            WHERE user_id=#{userId} AND folder_id=#{folderId}
            """)
    int renameOwned(@Param("userId") Long userId, @Param("folderId") Long folderId,
                    @Param("name") String name, @Param("nameKey") String nameKey,
                    @Param("updateTime") LocalDateTime updateTime);

    /**
     * @param userId 夹所有者
     * @param folderId 已在同一事务完成收藏迁移的本人夹
     * @return 实际删除行数
     */
    @Delete("DELETE FROM t_skill_favorite_folder WHERE user_id=#{userId} AND folder_id=#{folderId}")
    int deleteOwned(@Param("userId") Long userId, @Param("folderId") Long folderId);

    /**
     * @param userId 注销或补偿清理的账号，不要求账号仍正常
     * @return 实际删除行数
     */
    @Delete("DELETE FROM t_skill_favorite_folder WHERE user_id=#{userId}")
    int deleteAllOwned(@Param("userId") Long userId);
}
