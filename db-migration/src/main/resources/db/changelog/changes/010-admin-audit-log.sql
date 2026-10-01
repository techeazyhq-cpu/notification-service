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


--changeset notification:010-admin-audit-log splitStatements:false
--comment Append-only record of every state-changing admin API call, allowed or refused (see ADR-019).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'admin_audit_event'
CREATE TABLE admin_audit_event (
    id             UUID         PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    actor          VARCHAR(100),
    actor_role     VARCHAR(16),
    http_method    VARCHAR(10)  NOT NULL,
    route          VARCHAR(300),
    path           VARCHAR(500) NOT NULL,
    status_code    INTEGER      NOT NULL,
    outcome        VARCHAR(16)  NOT NULL,
    source_address VARCHAR(64),
    user_agent     VARCHAR(300),
    CONSTRAINT ck_admin_audit_event_role CHECK (actor_role IN ('VIEWER', 'OPERATOR', 'ADMIN')),
    CONSTRAINT ck_admin_audit_event_outcome CHECK (outcome IN ('SUCCEEDED', 'REJECTED', 'DENIED', 'FAILED'))
);

CREATE INDEX ix_admin_audit_event_time ON admin_audit_event (occurred_at DESC, id DESC);
CREATE INDEX ix_admin_audit_event_actor ON admin_audit_event (actor, occurred_at DESC);

CREATE FUNCTION reject_admin_audit_event_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tr_admin_audit_event_no_change BEFORE UPDATE OR DELETE ON admin_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_event_change();

CREATE TRIGGER tr_admin_audit_event_no_truncate BEFORE TRUNCATE ON admin_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admin_audit_event_change();
--rollback DROP TABLE admin_audit_event;
--rollback DROP FUNCTION reject_admin_audit_event_change();
