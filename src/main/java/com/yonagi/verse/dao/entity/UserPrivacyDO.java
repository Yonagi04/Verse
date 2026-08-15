package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/15 10:41
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_user_privacy")
public class UserPrivacyDO {

    private Long id;

    private Long userId;

    private Integer showBio;

    private Integer showRegion;

    private Integer showTimezone;

    private Date createTime;

    private Date updateTime;
}
