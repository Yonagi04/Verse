package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.dao.entity.NotificationRecipientDO;
import com.yonagi.verse.dto.req.NotificationListReqDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import com.yonagi.verse.dto.resp.NotificationRecentListRespDTO;
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

    @Select("SELECT n.notification_id, n.title, n.content, n.severity, n.create_time " +
            "FROM t_notification_recipient r " +
            "JOIN t_notification n ON r.notification_id = n.notification_id " +
            "WHERE r.user_id = #{userId} AND " +
            "n.tenant_id = #{tenantId} AND " +
            "n.create_time >= FROM_UNIXTIME(#{startTime} / 1000.0) AND " +
            "n.type = 'ANNOUNCEMENT' " +
            "ORDER BY r.create_time DESC")
    @Results({
            @Result(property = "notificationId", column = "notification_id"),
            @Result(property = "createTime", column = "create_time")
    })
    List<NotificationRecentListRespDTO.NotificationInfo> selectListByUserIdAndTenantIdAndStartTime(@Param("userId") Long userId,
                                                                                        @Param("tenantId") Long tenantId,
                                                                                        @Param("startTime") long startTime);

    /**
     * 分页查询通知列表（JOIN 两表，MyBatis-Plus 自动处理 COUNT 和分页）
     */
    @Select("<script>SELECT n.notification_id, n.title, n.content, n.type, n.severity, r.is_read, r.create_time " +
            "FROM t_notification_recipient r " +
            "JOIN t_notification n ON r.notification_id = n.notification_id " +
            "WHERE r.user_id = #{userId} AND r.create_time >= FROM_UNIXTIME(#{startTime} / 1000.0) " +
            "<if test='query.type != null'>AND n.type = #{query.type} </if>" +
            "<if test='query.severity != null'>AND n.severity = #{query.severity} </if>" +
            "<if test='query.isRead != null'>AND r.is_read = #{query.isRead} </if>" +
            "ORDER BY r.create_time DESC, r.id DESC</script>")
    @Results({
            @Result(property = "notificationId", column = "notification_id"),
            @Result(property = "isRead", column = "is_read"),
            @Result(property = "createTime", column = "create_time")
    })
    Page<NotificationListRespDTO.NotificationInfo> selectPageByUserIdAndStartTime(
            Page<?> page,
            @Param("userId") Long userId,
            @Param("startTime") long startTime,
            @Param("query") NotificationListReqDTO query);
}
