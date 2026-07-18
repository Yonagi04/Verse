package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户实体
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/05/18 19:36
 */
@Data
@TableName("t_user")
public class UserDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 用户唯一标识（业务ID）
     */
    private Long userId;

    /**
     * 登录用户名，注册之后就不可修改，也必须是唯一的，只能是字母、数字、下划线的组合
     */
    private String username;

    /**
     * 昵称，类似于姓名，可以修改，可以是汉字，字母，数字和符号
     */
    private String nickname;

    /**
     * 密码（BCrypt加密）
     */
    private String password;

    /**
     * 邮箱（业务层展示时脱敏处理）
     */
    private String email;

    /**
     * 手机号（业务层展示时脱敏处理）
     */
    private String phone;

    /**
     * 状态：0=禁用, 1=正常
     */
    private Integer status;

    /**
     * 当前活跃租户ID
     */
    private Long lastActiveTenantId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 逻辑删除：0=未删除, 1=已删除
     */
    private Integer delFlag;
}
