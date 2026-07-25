package com.yonagi.verse.common.database;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 10:05
 */
@Data
public class BaseDO {

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 逻辑删除，对于 UserDO 而言，0表示正常参与业务逻辑查询，1表示只参与身份验证的查询，业务不参与（因为删除了）
     */
    @TableField(fill = FieldFill.INSERT)
    private Integer delFlag;
}
