package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 15:03
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginDeviceRespDTO {

    private String deviceId;

    private String deviceName;

    private String region;

    private String ip;

    private Date lastLoginAt;

    private Boolean online;

    private Boolean currentDevice;
}
