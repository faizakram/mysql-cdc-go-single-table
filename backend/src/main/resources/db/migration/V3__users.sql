-- Application users for authentication (#55). Roles seed RBAC (#56): ADMIN | OPERATOR | VIEWER.
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,        -- BCrypt
    role          VARCHAR(20)  NOT NULL DEFAULT 'OPERATOR',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
