package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yonagi.verse.common.database.BaseDO;
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
public class UserDO extends BaseDO {

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
     * 邮箱（AES-256-GCM加密存储）
     */
    private String email;

    /**
     * 邮箱哈希（SHA-256，用于等值查询）
     */
    private String emailHash;

    /**
     * 手机号（AES-256-GCM加密存储）
     */
    private String phone;

    /**
     * 手机号哈希（SHA-256，用于等值查询）
     */
    private String phoneHash;

    /**
     * 状态：0=禁用, 1=正常, 2=注销
     */
    private Integer status;

    /**
     * 当前活跃租户ID
     */
    private Long lastActiveTenantId;

    /**
     * 账号注销时间
     */
    private Date cancelTime;
}
