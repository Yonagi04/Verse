package com.yonagi.verse.dao.mapper.skillhub;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账号内部发布关联的持久化访问，冻结状态不代表另一套用户资料。 */
public interface SkillPublisherMapper extends BaseMapper<SkillPublisherDO> {
    /**
     * @param userId 可信会话中的资源所有者
     * @return 已取得行锁的本人发布身份；尚未建立时null
     */
    @Select("""
            SELECT publisher_id,user_id,handle,public_name,status,revision,create_time,update_time
            FROM t_skill_publisher WHERE user_id=#{userId} FOR UPDATE
            """)
    SkillPublisherDO selectByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * @param publisherId 已验证资源引用的发布者标识
     * @return 持行锁的发布身份；不存在时null
     */
    @Select("""
            SELECT publisher_id,user_id,handle,public_name,status,revision,create_time,update_time
            FROM t_skill_publisher WHERE publisher_id=#{publisherId} FOR UPDATE
            """)
    SkillPublisherDO selectByIdForUpdate(@Param("publisherId") Long publisherId);
}
