package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.util.List;

/** 持久化清单不包含归档Base64或重复的完整Markdown。 */
record SkillPackageManifest(String packageName, String packageDescription, List<SkillValidatedFile> files) {
}
