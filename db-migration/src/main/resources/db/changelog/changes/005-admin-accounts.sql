--liquibase formatted sql

-- Copyright 2026 Vasantha Kumar
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- @author Vasantha Kumar <vasantha.kumar@hotmail.com>


--changeset notification:005-admin-accounts
--comment Admin accounts with password and optional TOTP two-factor authentication, recovery codes, and login sessions (see ADR-005).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'admin_user'
CREATE TABLE admin_user (
    id                  UUID PRIMARY KEY,
    username            VARCHAR(64)  NOT NULL UNIQUE,
    password_hash       VARCHAR(200) NOT NULL,
    password_changed_at TIMESTAMPTZ,
    totp_secret         VARCHAR(400),
    totp_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    totp_last_step      BIGINT       NOT NULL DEFAULT 0,
    failed_attempts     INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE admin_recovery_code (
    user_id   UUID         NOT NULL REFERENCES admin_user (id) ON DELETE CASCADE,
    code_hash VARCHAR(64)  NOT NULL,
    used_at   TIMESTAMPTZ,
    PRIMARY KEY (user_id, code_hash)
);

CREATE TABLE admin_session (
    token_hash   VARCHAR(64) PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES admin_user (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_admin_session_user ON admin_session (user_id);
--rollback DROP TABLE admin_session;
--rollback DROP TABLE admin_recovery_code;
--rollback DROP TABLE admin_user;
