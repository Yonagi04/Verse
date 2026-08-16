package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 邀请码计数事件去重表实体，以 event_id 作为幂等键。
 *
 * @author Yonagi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_counter_event")
public class CounterEventDO {

    private Long id;

    private String eventId;

    private Long inviteId;

    private Date createTime;
}
