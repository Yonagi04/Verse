package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/23 10:43
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LlmServiceRemovePreRespDTO {

    private String info;

    private String token;

    private Date expires;
}
