package ru.agimate.common.security;

public enum UserRole {
    GUEST, USER, ADMIN;

    public String toAuthority() {
        return "ROLE_" + this.name();
    }
}
