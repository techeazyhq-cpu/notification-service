--liquibase formatted sql

-- Copyright 2026 Vasantha Kumar Kesavan
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
-- @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>

--changeset notification:029-client-drafting-policy splitStatements:false
--comment Each client's AI drafting policy, set by an administrator (ADR-045): drafting on or off for that client, and its own monthly token budget in place of the default client budget (NULL keeps the default). A client without a row has drafting on with the default budget. A new table: nothing existing is locked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'client_drafting_policy'
CREATE TABLE client_drafting_policy (
    client_id            UUID         PRIMARY KEY REFERENCES client (id) ON DELETE CASCADE,
    drafting_enabled     BOOLEAN      NOT NULL,
    monthly_token_budget BIGINT,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by           VARCHAR(120) NOT NULL,
    CONSTRAINT ck_client_drafting_policy_budget CHECK (monthly_token_budget IS NULL OR monthly_token_budget >= 0)
);
--rollback DROP TABLE client_drafting_policy;
