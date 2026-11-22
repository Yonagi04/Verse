package com.yonagi.verse.dao.entity.skillhub;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 个人收藏数据，独立物理删除，不继承租户基类。 */
@Getter
@Setter
@TableName("t_skill_favorite_folder")
public class SkillFavoriteFolderDO {
    /** 自定义收藏夹标识，不存默认夹。 */
    @TableId(type = IdType.INPUT)
    private Long folderId;
    /** 个人账号归属。 */
    private Long userId;
    /** 规范化后的展示名称。 */
    private String name;
    /** NFC 小写唯一键，采用二进制排序规则。 */
    private String nameKey;
    /** 创建时间，UTC。 */
    private LocalDateTime createTime;
    /** 最近改名时间，UTC。 */
    private LocalDateTime updateTime;
}
