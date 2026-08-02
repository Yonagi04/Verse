package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:31
 */
@Mapper
public interface NotificationRecipientMapper extends BaseMapper<NotificationRecipientDO> {

    @Select("SELECT n.notification_id, n.title, n.content, n.type, n.severity, " +
            "r.is_read, r.create_time " +
            "FROM t_notification_recipient r " +
            "JOIN t_notification n ON r.notification_id = n.notification_id " +
            "WHERE r.user_id = #{userId} AND r.create_time >= FROM_UNIXTIME(#{startTime} / 1000) " +
            "ORDER BY r.create_time DESC")
    @Results({
            @Result(property = "notificationId", column = "notification_id"),
            @Result(property = "isRead", column = "is_read"),
            @Result(property = "createTime", column = "create_time")
    })
    List<NotificationListRespDTO.NotificationInfo> selectListByUserIdAndStartTime(@Param("userId") Long userId, @Param("startTime") long startTime);
}
