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



--changeset notification:014-billing-otp-price
--comment Each tenant's own price per one-time password and channel, instead of its plan's price (ADR-034). A new table, so nothing existing is locked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'billing_account_otp_price'
CREATE TABLE billing_account_otp_price (
    client_id  UUID          NOT NULL REFERENCES billing_account (client_id) ON DELETE CASCADE,
    channel    VARCHAR(16)   NOT NULL,
    unit_price NUMERIC(19,6) NOT NULL,
    currency   VARCHAR(3)    NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (client_id, channel),
    CONSTRAINT ck_otp_price_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP', 'PUSH')),
    CONSTRAINT ck_otp_price_amount CHECK (unit_price >= 0)
);
--rollback DROP TABLE billing_account_otp_price;
