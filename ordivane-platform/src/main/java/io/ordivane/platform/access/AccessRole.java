package io.ordivane.platform.access;

/** 互不继承的业务角色；操作员不能据此获得审批权限。 */
public enum AccessRole {
    OBSERVER, OPERATOR, APPROVER
}
