package com.yonagi.verse.dao.entity;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.*;
@Data
@TableName("t_external_identity")
public class ExternalIdentityDO {
    /** 身份ID */
    @TableId(type = IdType.INPUT)
    private Long id;
    /** 平台 */
    private String provider;
    /** 可信发行方 */
    private String issuer;
    /** 稳定主体标识 */
    private String subject;
    /** 外部用户名快照 */
    private String externalUsername;
    /** 显示名快照 */
    private String displayName;
    /** 加密邮箱 */
    private String emailEncrypted;
    /** 平台邮箱验证声明 */
    private Boolean emailVerified;
    /** 关系版本 */
    private Long bindingVersion;
    /** 快照更新时间 */
    private java.time.LocalDateTime profileUpdatedAt;
}
