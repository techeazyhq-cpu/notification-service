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

--changeset notification:028-template-draft-usage-push splitStatements:false
--comment Push notification templates can be drafted too (ADR-044), so the drafting ledger accepts PUSH. The new check is added NOT VALID and validated separately, so the table is not locked while existing rows are checked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_constraint WHERE conname = 'ck_template_draft_usage_channel' AND pg_get_constraintdef(oid) LIKE '%PUSH%'
ALTER TABLE template_draft_usage DROP CONSTRAINT ck_template_draft_usage_channel;
ALTER TABLE template_draft_usage ADD CONSTRAINT ck_template_draft_usage_channel
    CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP', 'PUSH')) NOT VALID;
ALTER TABLE template_draft_usage VALIDATE CONSTRAINT ck_template_draft_usage_channel;
--rollback ALTER TABLE template_draft_usage DROP CONSTRAINT ck_template_draft_usage_channel;
--rollback ALTER TABLE template_draft_usage ADD CONSTRAINT ck_template_draft_usage_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP'));
