ALTER TABLE cloud_sessions ADD permission_mode VARCHAR(16) NULL;
ALTER TABLE cloud_sessions MODIFY COLUMN profile_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions MODIFY COLUMN cluster_id VARCHAR(36) NULL;
ALTER TABLE cloud_sessions MODIFY COLUMN namespace VARCHAR(63) NULL;
ALTER TABLE cloud_sessions MODIFY COLUMN image_reference VARCHAR(512) NULL;
