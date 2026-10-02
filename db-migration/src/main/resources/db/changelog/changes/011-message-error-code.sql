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


--changeset notification:011-message-error-code
--comment Why a message failed or is being retried, as a code from the error dictionary (ADR-031).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'notification_message' AND column_name = 'error_code'
ALTER TABLE notification_message ADD COLUMN error_code VARCHAR(40);

UPDATE notification_message SET error_code = CASE failure_kind
        WHEN 'EXHAUSTED' THEN 'DELIVERY_ATTEMPTS_EXHAUSTED'
        WHEN 'DEAD_LETTERED' THEN 'DELIVERY_DEAD_LETTERED'
        ELSE 'DELIVERY_REJECTED'
    END
WHERE status = 'FAILED';

UPDATE notification_message SET error_code = 'PROVIDER_TEMPORARILY_FAILING' WHERE status = 'RETRYING';
--rollback ALTER TABLE notification_message DROP COLUMN error_code;
