ALTER TABLE cloud_sessions ADD kind VARCHAR(16) DEFAULT 'cloud' NOT NULL;
ALTER TABLE cloud_sessions ADD host_device_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions ADD host_directory_name VARCHAR(255) NULL;
CREATE INDEX cloud_session_owner_kind ON cloud_sessions (team_id, owner_id, kind, created_at);
CREATE INDEX cloud_session_team_kind ON cloud_sessions (team_id, kind, created_at);
ALTER TABLE cloud_sessions ADD CONSTRAINT cloud_sessions_host_device FOREIGN KEY (host_device_id) REFERENCES devices(id);
