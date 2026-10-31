package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;

/** 仅下载用例使用的私有资源快照，不得直接作为HTTP或日志对象。 */
@Getter
@Setter
public class SkillDownloadProjection {
    private Long skillId;
    private Long publisherId;
    private Long userId;
    private Long versionId;
    private Long artifactId;
    private String slug;
    private String versionLabel;
    private String archiveKey;
    private String archiveSha256;
    private Long archiveBytes;
}
