package com.yonagi.verse.dao.mapper.skillhub;

import com.yonagi.verse.dao.entity.skillhub.*;
import com.yonagi.verse.dao.projection.SkillCommentResourceProjection;
import org.apache.ibatis.annotations.Param;

/** 评论用例的轻量引用与共享当前读；锁序由Service集中控制。 */
public interface SkillCommentAccessMapper {
    /** @return 不过滤生命周期的资源引用，仅用于查找不可变归属。 */
    SkillCommentResourceProjection reference(@Param("skillId") Long skillId);
    /** @return 使用现有Skill公开可用谓词的轻量资源。 */
    SkillCommentResourceProjection available(@Param("skillId") Long skillId);
    /** @return 持共享锁的发布者，不读取用户私有资料。 */
    SkillPublisherDO lockPublisher(@Param("id") Long id);
    /** @return 持共享锁的Skill。 */
    SkillDO lockSkill(@Param("id") Long id);
    /** @return 持共享锁的当前版本可用字段。 */
    SkillVersionDO lockVersion(@Param("id") Long id);
    /** @return 持共享锁的制品归属与状态。 */
    SkillArtifactDO lockArtifact(@Param("id") Long id);
}
