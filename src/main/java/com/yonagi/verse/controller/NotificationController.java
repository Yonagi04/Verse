package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.NotificationErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.NotificationListReqDTO;
import com.yonagi.verse.dto.resp.NotificationInfoRespDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import com.yonagi.verse.dto.resp.NotificationRecentListRespDTO;
import com.yonagi.verse.dto.resp.NotificationUnreadCountRespDTO;
import com.yonagi.verse.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:33
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Result<NotificationListRespDTO> getNotificationList(@CurrentUser Long userId,
                                                               @Valid @ModelAttribute NotificationListReqDTO requestParam) {
        return Results.success(notificationService.getNotificationList(userId, requestParam));
    }

    @GetMapping("/{notificationId}")
    public Result<NotificationInfoRespDTO> getNotification(@CurrentUser Long userId, @PathVariable Long notificationId) {
        if (notificationId == null) {
            throw new ClientException(NotificationErrorCodeEnum.NOTIFICATION_NOT_FOUND);
        }
        return Results.success(notificationService.getNotification(userId, notificationId));
    }

    @GetMapping("/unread-count")
    public Result<NotificationUnreadCountRespDTO> getUnreadNotificationCount(@CurrentUser Long userId) {
        return Results.success(notificationService.getUnreadNotificationCount(userId));
    }

    @PostMapping("/read-all")
    public Result<Integer> readAllUnreadNotifications(@CurrentUser Long userId) {
        return Results.success(notificationService.readAllUnreadNotifications(userId));
    }

    @GetMapping("/recent")
    public Result<NotificationRecentListRespDTO> getRecentNotifications(@CurrentUser Long userId,
                                                                        @RequestParam Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        return Results.success(notificationService.getRecentNotifications(userId, tenantId));
    }
}
