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

--changeset notification:020-idempotency-payload-fingerprint
--comment A keyed fingerprint of each request's payload, so reusing an Idempotency-Key for a different request is refused instead of silently answered with the first request. A new nullable column: nothing existing is locked or rewritten, and earlier requests (no fingerprint) are replayed as before.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'notification_request' AND column_name = 'payload_fingerprint'
ALTER TABLE notification_request ADD COLUMN payload_fingerprint VARCHAR(64);
--rollback ALTER TABLE notification_request DROP COLUMN payload_fingerprint;
