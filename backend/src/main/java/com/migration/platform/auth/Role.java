package com.migration.platform.auth;

/** Roles seed the RBAC epic (#56). ADMIN: full; OPERATOR: run/control; VIEWER: read-only. */
public enum Role {
    ADMIN,
    OPERATOR,
    VIEWER
}
