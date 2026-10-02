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


--changeset notification:012-message-category
--comment What each request, message and template is for; one-time passwords are delivered with priority and expire (ADR-033).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'notification_message' AND column_name = 'category'
ALTER TABLE notification_request ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'TRANSACTIONAL';
ALTER TABLE notification_request ADD CONSTRAINT ck_request_category CHECK (category IN ('OTP', 'TRANSACTIONAL', 'PROMOTIONAL'));
ALTER TABLE notification_request ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE notification_message ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'TRANSACTIONAL';
ALTER TABLE notification_message ADD CONSTRAINT ck_message_category CHECK (category IN ('OTP', 'TRANSACTIONAL', 'PROMOTIONAL'));
ALTER TABLE notification_message ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE template ADD COLUMN category VARCHAR(16);
ALTER TABLE template ADD CONSTRAINT ck_template_category CHECK (category IN ('OTP', 'TRANSACTIONAL', 'PROMOTIONAL'));

ALTER TABLE notification_message DROP CONSTRAINT ck_message_failure_kind;
ALTER TABLE notification_message ADD CONSTRAINT ck_message_failure_kind CHECK (failure_kind IN ('PERMANENT', 'EXHAUSTED', 'DEAD_LETTERED', 'EXPIRED'));

CREATE INDEX ix_message_pending_otp ON notification_message (updated_at) WHERE status = 'PENDING' AND category = 'OTP';
--rollback DROP INDEX ix_message_pending_otp;
--rollback UPDATE notification_message SET failure_kind = 'PERMANENT' WHERE failure_kind = 'EXPIRED';
--rollback ALTER TABLE notification_message DROP CONSTRAINT ck_message_failure_kind;
--rollback ALTER TABLE notification_message ADD CONSTRAINT ck_message_failure_kind CHECK (failure_kind IN ('PERMANENT', 'EXHAUSTED', 'DEAD_LETTERED'));
--rollback ALTER TABLE template DROP COLUMN category;
--rollback ALTER TABLE notification_message DROP COLUMN expires_at;
--rollback ALTER TABLE notification_message DROP COLUMN category;
--rollback ALTER TABLE notification_request DROP COLUMN expires_at;
--rollback ALTER TABLE notification_request DROP COLUMN category;
