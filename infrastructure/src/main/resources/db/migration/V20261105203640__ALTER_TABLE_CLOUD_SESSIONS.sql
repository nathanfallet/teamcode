ALTER TABLE cloud_sessions ADD parent_session_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions ADD root_session_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions ADD `depth` INT DEFAULT 0 NOT NULL;
CREATE INDEX cloud_session_parent ON cloud_sessions (parent_session_id, created_at, id);
CREATE INDEX cloud_session_parent_state ON cloud_sessions (parent_session_id, `state`, desired_state);