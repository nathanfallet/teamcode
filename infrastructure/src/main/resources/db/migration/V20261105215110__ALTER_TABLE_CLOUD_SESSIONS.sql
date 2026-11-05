ALTER TABLE cloud_sessions ADD run_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions ADD CONSTRAINT cloud_session_run UNIQUE (team_id, run_id);