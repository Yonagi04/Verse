package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 18:35
 */
@Data
@Builder
@TableName("t_login_history")
public class LoginHistoryDO {

    private Long id;

    private Long userId;

    private Date loginTime;

    private String deviceName;

    private String ip;

    private String region;

    private String result;

    private String failReason;
}
