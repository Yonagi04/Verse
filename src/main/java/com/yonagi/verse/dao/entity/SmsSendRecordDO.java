package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sms_send_record")
public class SmsSendRecordDO extends MessageSendRecordDO {
    /** 请求的短信签名。 */
    private String signName;
    /** 请求的模板代码。 */
    private String templateCode;
}
