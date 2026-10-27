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

--changeset notification:025-template-draft-usage splitStatements:false
--comment One row per AI template draft that reached the model, with the tokens it spent; the monthly drafting budgets are sums over it (ADR-041). Never the brief or the draft. Append-only, like admin_audit_event. A new table: nothing existing is locked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'template_draft_usage'
CREATE TABLE template_draft_usage (
    id            UUID         PRIMARY KEY,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    actor         VARCHAR(120) NOT NULL,
    client_id     UUID,
    channel       VARCHAR(16)  NOT NULL,
    model         VARCHAR(64)  NOT NULL,
    input_tokens  BIGINT       NOT NULL,
    output_tokens BIGINT       NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    CONSTRAINT ck_template_draft_usage_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP')),
    CONSTRAINT ck_template_draft_usage_outcome CHECK (outcome IN ('DRAFTED', 'OUTPUT_REJECTED', 'DECLINED', 'FAILED')),
    CONSTRAINT ck_template_draft_usage_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0)
);

CREATE INDEX ix_template_draft_usage_time ON template_draft_usage (occurred_at);
CREATE INDEX ix_template_draft_usage_client ON template_draft_usage (client_id, occurred_at) WHERE client_id IS NOT NULL;

CREATE FUNCTION reject_template_draft_usage_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'template_draft_usage is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tr_template_draft_usage_no_change BEFORE UPDATE OR DELETE ON template_draft_usage
    FOR EACH ROW EXECUTE FUNCTION reject_template_draft_usage_change();
--rollback DROP TABLE template_draft_usage;
--rollback DROP FUNCTION reject_template_draft_usage_change();
