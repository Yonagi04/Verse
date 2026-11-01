package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 公开查询的定列投影，只由查询Service转成HTTP模型。 */
@Getter
@Setter
public class SkillPublicProjection {
    private Long skillId;
    private Long publisherId;
    private String handle;
    private String publicName;
    private String slug;
    private String name;
    private String summary;
    private String categoryId;
    private Long revisionId;
    private Long versionId;
    private String versionLabel;
    private LocalDateTime publishedAt;
    private String skillMarkdown;
    private String declarationsJson;
    private Long archiveBytes;
    private String archiveSha256;
    private String sourceType;
    private String sourceJson;
}
