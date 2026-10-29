CREATE TABLE IF NOT EXISTS waits (id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL, resolver VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL, assignee_id VARCHAR(36) NOT NULL, created_by_id VARCHAR(36) NULL, title VARCHAR(200) NOT NULL, body MEDIUMTEXT NULL, refs text NOT NULL, field_defs MEDIUMTEXT NOT NULL, actions text NOT NULL, answer_action VARCHAR(64) NULL, answer_values MEDIUMTEXT NULL, answer_author_id VARCHAR(36) NULL, idempotency_key VARCHAR(128) NULL, created_at BIGINT NOT NULL, resolved_at BIGINT NULL, CONSTRAINT waits_session FOREIGN KEY (session_id) REFERENCES cloud_sessions(id) ON DELETE CASCADE, CONSTRAINT waits_assignee FOREIGN KEY (assignee_id) REFERENCES members(id));
CREATE INDEX wait_assignee ON waits (assignee_id, team_id, status, created_at, id);
CREATE INDEX wait_team ON waits (team_id, status, created_at, id);
CREATE INDEX wait_assignee_all ON waits (assignee_id, team_id, created_at, id);
CREATE INDEX wait_team_all ON waits (team_id, created_at, id);
CREATE INDEX wait_session ON waits (session_id, status);
ALTER TABLE waits ADD CONSTRAINT wait_idempotency UNIQUE (created_by_id, idempotency_key);
