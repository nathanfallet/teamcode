ALTER TABLE waits ADD child_session_id VARCHAR(36) NULL;
CREATE INDEX wait_child ON waits (child_session_id, status);