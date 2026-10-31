package com.yonagi.verse.dao.projection;

import lombok.Getter;
import lombok.Setter;

/** 用户域公开资料投影，SQL已遮蔽注销/禁用用户的名称与头像。 */
@Getter
@Setter
public class UserPublicProfileProjection {
    private Long userId;
    private String displayName;
    private String avatar;
}
