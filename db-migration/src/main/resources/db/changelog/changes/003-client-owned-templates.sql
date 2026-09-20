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


--changeset notification:003-client-owned-templates
--comment Client-owned templates; requests carry a snapshot of their content (formerly Flyway V3, see ADR-002).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'template' AND column_name = 'client_id'
-- client_id NULL = shared (admin-managed, visible to every client).
ALTER TABLE template ADD COLUMN client_id UUID REFERENCES client (id) ON DELETE CASCADE;

-- Names are unique per owner, and unique among shared templates.
ALTER TABLE template DROP CONSTRAINT template_name_key;
CREATE UNIQUE INDEX ux_template_shared_name ON template (name) WHERE client_id IS NULL;
CREATE UNIQUE INDEX ux_template_client_name ON template (client_id, name) WHERE client_id IS NOT NULL;

-- Requests carry a snapshot of the content they were accepted with, so editing or deleting a template never changes
-- or breaks requests in flight or in history. template_id stays as a reference only.
UPDATE notification_request r
   SET subject = t.subject, body = t.body
  FROM template t
 WHERE r.template_id = t.id AND r.body IS NULL;

ALTER TABLE notification_request DROP CONSTRAINT notification_request_template_id_fkey;
ALTER TABLE notification_request
    ADD CONSTRAINT notification_request_template_id_fkey
    FOREIGN KEY (template_id) REFERENCES template (id) ON DELETE SET NULL;
--rollback DELETE FROM template WHERE client_id IS NOT NULL;
--rollback ALTER TABLE notification_request DROP CONSTRAINT notification_request_template_id_fkey;
--rollback ALTER TABLE notification_request ADD CONSTRAINT notification_request_template_id_fkey FOREIGN KEY (template_id) REFERENCES template (id);
--rollback DROP INDEX ux_template_client_name;
--rollback DROP INDEX ux_template_shared_name;
--rollback ALTER TABLE template ADD CONSTRAINT template_name_key UNIQUE (name);
--rollback ALTER TABLE template DROP COLUMN client_id;
-- Note on undoing this change: it deletes client-owned templates (they do not exist in the previous schema). Requests
-- are not affected: they keep their content snapshot, and the delete runs first while ON DELETE SET NULL is still in
-- place, so requests that referenced a deleted template just lose that reference.
