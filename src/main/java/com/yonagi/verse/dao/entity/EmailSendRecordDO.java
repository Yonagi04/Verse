package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_email_send_record")
public class EmailSendRecordDO extends MessageSendRecordDO {
    /** 邮件主题摘要，主题本身也可能包含隐私。 */
    private String subjectHash;
}
