package com.yonagi.verse.dao.projection;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 当前租户数据库投影。
 */
@Data
@Accessors(chain = true)
public class CurrentTenantState {

    /** 用户数据库中保存的当前租户 ID。 */
    private Long storedTenantId;

    /** 通过租户、成员关系与角色校验后的租户 ID。 */
    private Long tenantId;

    /** 当前租户名称。 */
    private String name;

    /** 当前租户类型。 */
    private String type;

    /** 用户在当前租户中的合法角色。 */
    private String role;

    /** 本次解析是否修复了数据库状态。 */
    private boolean repaired;

    /** 是否存在可用于授权的有效租户上下文。 */
    public boolean isValid() {
        return tenantId != null && role != null;
    }
}
