package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/08 12:56
 */
@Data
public class TenantSendNotificationReqDTO {

    @NotBlank(message = "消息的严重程度不能为空")
    private String severity;

    @NotBlank(message = "消息标题不能为空")
    @Length(max = 50, message = "消息标题长度不能超过50个字")
    private String title;

    @NotBlank(message = "消息内容不能为空")
    @Length(max = 500, message = "消息内容长度不能超过500个字")
    private String content;

    /**
     * 接收者ID类型，1表示全员接收，2表示只有MEMBER才接收，3表示只有管理员才接收
     */
    @NotNull(message = "接收者类型不能为空")
    private Integer receiverType;
}
