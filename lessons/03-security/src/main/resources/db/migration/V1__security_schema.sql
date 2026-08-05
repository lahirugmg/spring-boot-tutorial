-- This module shares the `appdb` database with module 02 but keeps its own Flyway history
-- table (see spring.flyway.table in application.yml), so the two modules' migrations never
-- interfere with each other.

CREATE SEQUENCE app_user_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE document_seq  START WITH 1 INCREMENT BY 50;

CREATE TABLE app_user (
    id             BIGINT       PRIMARY KEY,
    username       VARCHAR(100) NOT NULL,
    -- Wide enough for any modern hash. BCrypt is 60 chars; Argon2 and scrypt are longer,
    -- and a DelegatingPasswordEncoder prefixes the id, e.g. "{bcrypt}$2a$10$...".
    -- Sizing this column to 60 is a classic migration-day outage.
    password_hash  VARCHAR(255) NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    account_locked BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE TABLE app_user_role (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(50) NOT NULL,
    CONSTRAINT pk_app_user_role  PRIMARY KEY (user_id, role),
    CONSTRAINT fk_app_user_role  FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE document (
    id             BIGINT       PRIMARY KEY,
    title          VARCHAR(200) NOT NULL,
    owner_username VARCHAR(100) NOT NULL,
    classification VARCHAR(20)  NOT NULL,
    content        TEXT
);

CREATE INDEX idx_document_owner ON document (owner_username);
