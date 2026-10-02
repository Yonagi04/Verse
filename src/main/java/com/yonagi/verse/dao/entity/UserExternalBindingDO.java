package com.yonagi.verse.dao.entity;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.*;
@Data
@TableName("t_user_external_binding")
public class UserExternalBindingDO {
    /** 关系ID */
    @TableId(type = IdType.INPUT)
    private Long id;
    /** 本站用户ID */
    private Long userId;
    /** 外部身份ID */
    private Long externalIdentityId;
    /** 平台 */
    private String provider;
    /** 绑定时间 */
    private java.time.LocalDateTime boundAt;
}
