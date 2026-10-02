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



--changeset notification:013-otp-sweep-index runInTransaction:false
--comment The sweeper finds stuck one-time passwords in every in-flight status, not only PENDING (ADR-033 amendment). Built concurrently so writes are not blocked; a build left invalid by a failure is dropped and rebuilt.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relname = 'ix_message_inflight_otp' AND i.indisvalid
DROP INDEX CONCURRENTLY IF EXISTS ix_message_inflight_otp;
CREATE INDEX CONCURRENTLY ix_message_inflight_otp ON notification_message (status, updated_at)
    WHERE category = 'OTP' AND status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRYING');
DROP INDEX CONCURRENTLY IF EXISTS ix_message_pending_otp;
--rollback CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_message_pending_otp ON notification_message (updated_at) WHERE status = 'PENDING' AND category = 'OTP';
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_message_inflight_otp;
