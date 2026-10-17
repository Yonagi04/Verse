package com.yonagi.verse.dao.projection;
import lombok.Data;
@Data
public class CostAlertRecipient {
    /** 用户标识。 */ private Long userId;
    /** 当前角色。 */ private String role;
    /** 手机密文。 */ private String phone;
    /** 邮件密文。 */ private String email;
    /** 手机摘要。 */ private String phoneHash;
    /** 邮件摘要。 */ private String emailHash;
}
