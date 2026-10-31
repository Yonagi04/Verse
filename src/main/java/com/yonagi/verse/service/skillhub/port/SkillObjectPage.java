package com.yonagi.verse.service.skillhub.port;

import java.util.List;

/** 对象发现保持有界，不把存储SDK类型暴露到业务用例。 */
public record SkillObjectPage(List<SkillStoredObject> objects, String nextCursor) {
}
