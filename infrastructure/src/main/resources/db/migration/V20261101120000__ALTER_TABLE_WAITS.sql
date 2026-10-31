ALTER TABLE waits ADD due_at BIGINT NULL;
ALTER TABLE waits ADD event_source VARCHAR(16) NULL;
ALTER TABLE waits ADD on_timeout VARCHAR(16) NULL;
ALTER TABLE waits ADD resume_message text NULL;
ALTER TABLE waits ADD event_token_hash VARCHAR(64) NULL;
ALTER TABLE waits ADD wake_pending BOOLEAN DEFAULT FALSE NOT NULL;
CREATE INDEX wait_due ON waits (status, due_at);
CREATE INDEX wait_wake ON waits (wake_pending, resolved_at);
CREATE INDEX wait_assignee_resolver ON waits (assignee_id, team_id, status, resolver, created_at, id);
