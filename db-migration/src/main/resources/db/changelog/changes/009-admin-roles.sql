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


--changeset notification:009-admin-roles
--comment Role-based access for administrators: VIEWER, OPERATOR, ADMIN (see ADR-015). Existing administrators keep full access.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'admin_user' AND column_name = 'role'
ALTER TABLE admin_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role CHECK (role IN ('VIEWER', 'OPERATOR', 'ADMIN'));
ALTER TABLE admin_user ALTER COLUMN role DROP DEFAULT;
--rollback ALTER TABLE admin_user DROP CONSTRAINT ck_admin_user_role;
--rollback ALTER TABLE admin_user DROP COLUMN role;
