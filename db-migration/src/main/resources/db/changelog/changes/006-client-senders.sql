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


--changeset notification:006-client-senders
--comment Client-owned, verified sender addresses for EMAIL, and the sender snapshot on each request (see ADR-007).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'client_sender'
CREATE TABLE client_sender (
    id                 UUID PRIMARY KEY,
    client_id          UUID         NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    email              VARCHAR(254) NOT NULL,
    display_name       VARCHAR(120),
    status             VARCHAR(16)  NOT NULL,
    is_default         BOOLEAN      NOT NULL DEFAULT FALSE,
    token_hash         VARCHAR(64),
    token_expires_at   TIMESTAMPTZ,
    verification_sent_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    verified_at        TIMESTAMPTZ,
    CONSTRAINT ck_client_sender_status CHECK (status IN ('PENDING', 'VERIFIED'))
);

CREATE UNIQUE INDEX ux_client_sender_email ON client_sender (client_id, lower(email));
CREATE UNIQUE INDEX ux_client_sender_default ON client_sender (client_id) WHERE is_default;
CREATE UNIQUE INDEX ux_client_sender_token ON client_sender (token_hash) WHERE token_hash IS NOT NULL;

ALTER TABLE notification_request ADD COLUMN sender_email VARCHAR(254);
ALTER TABLE notification_request ADD COLUMN sender_name VARCHAR(120);
--rollback ALTER TABLE notification_request DROP COLUMN sender_name;
--rollback ALTER TABLE notification_request DROP COLUMN sender_email;
--rollback DROP TABLE client_sender;
