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

-- Client-owned templates. client_id NULL = shared (admin-managed, visible to every client).
ALTER TABLE template ADD COLUMN client_id UUID REFERENCES client (id) ON DELETE CASCADE;

-- Names are unique per owner, and unique among shared templates.
ALTER TABLE template DROP CONSTRAINT template_name_key;
CREATE UNIQUE INDEX ux_template_shared_name ON template (name) WHERE client_id IS NULL;
CREATE UNIQUE INDEX ux_template_client_name ON template (client_id, name) WHERE client_id IS NOT NULL;

-- Requests carry a snapshot of the content they were accepted with (already true for inline content), so editing or
-- deleting a template never changes or breaks requests in flight or in history. template_id stays as a reference only.
UPDATE notification_request r
   SET subject = t.subject, body = t.body
  FROM template t
 WHERE r.template_id = t.id AND r.body IS NULL;

ALTER TABLE notification_request DROP CONSTRAINT notification_request_template_id_fkey;
ALTER TABLE notification_request
    ADD CONSTRAINT notification_request_template_id_fkey
    FOREIGN KEY (template_id) REFERENCES template (id) ON DELETE SET NULL;
