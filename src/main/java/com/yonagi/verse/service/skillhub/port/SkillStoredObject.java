package com.yonagi.verse.service.skillhub.port;

import java.time.Instant;

/** 仅清理协调使用的私有对象元信息。 */
public record SkillStoredObject(String key, Instant lastModified) {
}
