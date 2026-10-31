package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 本人管理页定列投影，不含包正文、内部对象Key及声明。 */
@Getter
@Setter
public class SkillOwnerProjection {
    private Long skillId;
    private Long draftId;
    private String name;
    private String slug;
    private String status;
    private String draftStatus;
    private String jobStatus;
    private String versionLabel;
    private Long revision;
    private LocalDateTime updateTime;
}
