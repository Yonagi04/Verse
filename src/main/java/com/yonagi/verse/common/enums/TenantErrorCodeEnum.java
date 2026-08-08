package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * 租户相关错误码
 *
 * @author Yonagi
 */
public enum TenantErrorCodeEnum implements IErrorCode {

    TENANT_CREATE_ERROR("A000300", "租户创建失败"),
    TENANT_INVITE_CODE_CREATE_ERROR("A000301", "租户邀请码创建失败"),
    TENANT_JOIN_ERROR("A000302", "加入租户失败"),
    TENANT_UPDATE_ERROR("A000303", "租户更新失败"),
    TENANT_SWITCH_ERROR("A000304", "切换租户失败"),
    TENANT_CLOSE_ERROR("A000305", "租户关闭失败"),
    TENANT_LEAVE_ERROR("A000306", "离开租户失败"),
    INVITE_CODE_DEACTIVATE_ERROR("A000307", "邀请码禁用失败"),
    INVITE_CODE_ACTIVATE_ERROR("A000308", "邀请码启用失败"),
    REQUEST_STATUS_UPDATE_ERROR("A000309", "申请单审批失败"),
    TENANT_JOIN_REQUEST_CREATE_ERROR("A000310", "加入租户申请单创建失败"),
    TENANT_NOTIFICATION_PUSH_ERROR("A000311", "发送并推送租户内通知失败"),

    TENANT_NOT_EXIST("B000300", "租户不存在"),
    TENANT_ID_IS_NULL("B000301", "租户ID不能为空"),
    TENANT_PERMISSION_DENIED("B000302", "无租户操作权限"),
    TENANT_COUNT_EXCEEDS("B000303", "用户最多只能加入/创建10个租户"),
    TENANT_INVITE_CODE_EXPIRED("B000304", "租户邀请码过期"),
    TENANT_HAS_BEEN_JOINED("B000305", "已加入此租户"),
    TENANT_JOIN_PROHIBITED("B000306", "此租户不能加入"),
    TENANT_MEMBER_ID_IS_NULL("B000307", "成员ID不能为空"),
    TENANT_NOT_JOINED("B000308", "用户未加入该租户"),
    TENANT_CAN_NOT_CLOSE("B000309", "该租户不能被关闭"),
    TENANT_CLOSE_TOKEN_EXPIRED("B000310", "租户关闭Token过期"),
    TENANT_NAME_ERROR("B000311", "租户名称不正确"),
    TENANT_CAN_NOT_LIST_MEMBERS("B000312", "该租户不能查看成员列表"),
    TENANT_MEMBER_UPDATE_ID_SAME("B000313", "不能修改自己的角色"),
    TENANT_MEMBER_CAN_NOT_UPDATE("B000314", "该成员不能被修改"),
    TENANT_MEMBER_NOT_JOINED("B000315", "该成员未加入租户"),
    TENANT_MEMBER_REMOVE_ID_SAME("B000316", "不能移除自己"),
    TENANT_MEMBER_CAN_NOT_REMOVE("B000317", "该成员不能被移除"),
    TENANT_MEMBER_ROLE_ERROR("B000318", "角色不正确"),
    USER_ID_IS_NULL("B000319", "用户ID不能为空"),
    PERSONAL_TENANT_CAN_NOT_LEAVE("B000320", "不能离开个人租户"),
    INVITE_CODE_GENE_PER_DAY_LIMIT("B000321", "已达到邀请码的单日生成上限，请明天再试或使用已有的邀请码"),
    INVITE_CODE_CAN_NOT_GENE("B000322", "该租户不能生成邀请码"),
    INVITE_CODE_IS_NULL("B000323", "邀请码不能为空"),
    INVITE_CODE_NOT_FOUND("B000324", "邀请码不存在"),
    INVITE_CODE_CAN_NOT_DEACTIVATE("B000325", "该邀请码不能被禁用"),
    INVITE_CODE_CAN_NOT_ACTIVATE("B000326", "该邀请码不能被启用"),
    REQUEST_ID_IS_NULL("B000327", "申请单ID不能为空"),
    REQUEST_NOT_FOUND("B000328", "申请单不存在"),
    REQUEST_APPROVE_SELF_ERROR("B000329", "不能审批自己的申请单"),
    REQUEST_HAS_BEEN_REVIEWED("B000330", "该申请单已被审批"),
    TENANT_JOIN_REQUEST_PENDING_EXISTS("B000331", "您已提交过加入申请，请等待管理员审批"),
    SUPER_ADMIN_LEAVE_TENANT_ERROR("B000332", "超级管理员不能直接离开租户，请完成管理员交接后再离开"),
    TENANT_NOTIFICATION_SEND_PER_DAY_LIMIT("B000333", "已达到租户内消息的单日发送次数上限，请明天再试");

    private final String code;
    private final String message;

    TenantErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
