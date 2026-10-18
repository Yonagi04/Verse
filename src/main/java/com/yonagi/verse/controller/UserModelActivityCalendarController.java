package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.result.*;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserModelActivityCalendarErrorCode;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.resp.UserModelActivityCalendarRespDTO;
import com.yonagi.verse.service.user.activity.UserModelActivityCalendarQueryService;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

/** 用户模型成功调用活动日历入口。 */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/activity-calendar")
public class UserModelActivityCalendarController {
    private final UserModelActivityCalendarQueryService query;
    @GetMapping
    public Result<UserModelActivityCalendarRespDTO> getCalendar(@CurrentUser Long userId,@RequestParam(defaultValue="ROLLING_365") String view,@RequestParam(required=false) Integer year,
            HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if (!Set.of("view","year").containsAll(request.getParameterMap().keySet())) throw new ClientException(UserModelActivityCalendarErrorCode.INVALID_RANGE);
        return Results.success(query.getCalendar(userId,view,year));
    }
}
