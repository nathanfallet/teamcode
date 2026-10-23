ALTER TABLE cloud_sessions ADD host_folder_id VARCHAR(64) NULL;
CREATE INDEX cloud_session_host_waiting ON cloud_sessions (host_device_id, phase, desired_state, updated_at);