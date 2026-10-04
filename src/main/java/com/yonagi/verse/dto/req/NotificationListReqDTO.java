package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 通知列表分页与筛选条件。 */
@Data
public class NotificationListReqDTO {

    /** 页码，从 1 开始。 */
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于 1")
    private Integer pageNum = 1;

    /** 每页通知数量。 */
    @NotNull(message = "页面大小不能为空")
    @Min(value = 1, message = "页面大小必须大于等于 1")
    private Integer pageSize = 10;

    /** 通知类型，未传时查询全部类型。 */
    @Pattern(regexp = "SYSTEM|ANNOUNCEMENT", message = "通知类型无效")
    private String type;

    /** 通知严重级别，未传时查询全部级别。 */
    @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "通知严重级别无效")
    private String severity;

    /** 已读状态：0 未读、1 已读，未传时查询全部状态。 */
    @Min(value = 0, message = "通知已读状态无效")
    @Max(value = 1, message = "通知已读状态无效")
    private Integer isRead;
}
