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



--changeset notification:016-message-event-log splitStatements:false
--comment What happened to each message and when, and a fingerprint of its recipient that survives erasure, so a tenant can evidence its sending to an authority (ADR-035). New columns without defaults and a new table: nothing existing is locked or rewritten.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'message_event'
ALTER TABLE notification_message ADD COLUMN recipient_fingerprint VARCHAR(64);
ALTER TABLE notification_request ADD COLUMN template_name VARCHAR(120);

CREATE TABLE message_event (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id          UUID         NOT NULL REFERENCES notification_message (id) ON DELETE CASCADE,
    client_id           UUID         NOT NULL,
    event               VARCHAR(24)  NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,
    attempt             INTEGER,
    error_code          VARCHAR(40),
    provider_message_id VARCHAR(200),
    detail              VARCHAR(300),
    CONSTRAINT ck_message_event_type CHECK (event IN
        ('ATTEMPT_FAILED', 'SENT', 'FAILED', 'EXPIRED', 'DEAD_LETTERED', 'REQUEUED', 'DATA_ERASED'))
);

CREATE INDEX ix_message_event_message ON message_event (message_id, occurred_at);

CREATE FUNCTION reject_message_event_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'message_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tr_message_event_no_change BEFORE UPDATE ON message_event
    FOR EACH ROW EXECUTE FUNCTION reject_message_event_change();
--rollback DROP TABLE message_event;
--rollback DROP FUNCTION reject_message_event_change();
--rollback ALTER TABLE notification_request DROP COLUMN template_name;
--rollback ALTER TABLE notification_message DROP COLUMN recipient_fingerprint;
