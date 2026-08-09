package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 15:00
 */
@Data
@TableName("t_login_device")
public class LoginDeviceDO {

    private Long id;

    private String deviceId;

    private Long userId;

    private String deviceName;

    private String ip;

    private String region;

    private Integer status;

    private Date firstLoginAt;

    private Date lastLoginAt;
}
