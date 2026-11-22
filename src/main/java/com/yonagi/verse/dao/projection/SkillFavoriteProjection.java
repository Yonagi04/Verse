package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 收藏与当前公开卡片的定列投影；失效源列由 SQL 遮蔽。 */
@Getter
@Setter
public class SkillFavoriteProjection {
    private Long favoriteId;
    private Long skillId;
    private Long folderId;
    private String folderName;
    private LocalDateTime savedAt;
    private Boolean available;
    private SkillPublicProjection skill;
}
