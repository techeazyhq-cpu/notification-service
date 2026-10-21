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

--changeset notification:026-template-drafting-settings splitStatements:false
--comment The AI drafting settings an administrator edits in the admin UI (ADR-044): at most one row, which overrides the template-drafting.* defaults from the environment. The API key is encrypted with the secrets key (ADR-037). A new table: nothing existing is locked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'template_drafting_settings'
CREATE TABLE template_drafting_settings (
    id                          BOOLEAN      PRIMARY KEY DEFAULT TRUE,
    enabled                     BOOLEAN      NOT NULL,
    api_key                     TEXT,
    model                       VARCHAR(64)  NOT NULL,
    effort                      VARCHAR(8)   NOT NULL,
    max_tokens                  BIGINT       NOT NULL,
    monthly_token_budget        BIGINT       NOT NULL,
    client_monthly_token_budget BIGINT       NOT NULL,
    requests_per_minute         INTEGER      NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    updated_by                  VARCHAR(120) NOT NULL,
    CONSTRAINT ck_template_drafting_settings_single_row CHECK (id),
    CONSTRAINT ck_template_drafting_settings_effort CHECK (effort IN ('low', 'medium', 'high', 'xhigh', 'max')),
    CONSTRAINT ck_template_drafting_settings_limits CHECK (max_tokens > 0 AND monthly_token_budget >= 0
        AND client_monthly_token_budget >= 0 AND requests_per_minute > 0)
);
--rollback DROP TABLE template_drafting_settings;
