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
