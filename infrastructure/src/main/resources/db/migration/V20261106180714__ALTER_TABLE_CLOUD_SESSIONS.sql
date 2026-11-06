ALTER TABLE cloud_sessions ADD bypass_by VARCHAR(36) NULL;
ALTER TABLE cloud_sessions ADD bypass_credential_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions ADD bypass_at BIGINT NULL;