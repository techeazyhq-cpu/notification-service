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

--changeset notification:027-template-drafting-provider splitStatements:false
--comment AI drafting with any LLM provider (ADR-044): Anthropic, or an OpenAI-compatible API (OpenAI, Gemini, DeepSeek, Ollama, others) at an optional base URL. A row saved before this change stays on Anthropic. The table holds at most one row and a constant default is metadata-only, so nothing is rewritten or locked for long.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'template_drafting_settings' AND column_name = 'provider'
ALTER TABLE template_drafting_settings
    ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'ANTHROPIC',
    ADD COLUMN base_url VARCHAR(500),
    ADD CONSTRAINT ck_template_drafting_settings_provider CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GEMINI',
        'DEEPSEEK', 'OLLAMA', 'OPENAI_COMPATIBLE')),
    ADD CONSTRAINT ck_template_drafting_settings_base_url CHECK (base_url IS NOT NULL
        OR provider <> 'OPENAI_COMPATIBLE');
--rollback ALTER TABLE template_drafting_settings DROP CONSTRAINT ck_template_drafting_settings_base_url, DROP CONSTRAINT ck_template_drafting_settings_provider, DROP COLUMN base_url, DROP COLUMN provider;
