ALTER TABLE cloud_sessions ADD awaiting_reason VARCHAR(16) NULL;
ALTER TABLE cloud_sessions ADD awaiting_request_id VARCHAR(128) NULL;
ALTER TABLE cloud_sessions ADD awaiting_since BIGINT NULL;
ALTER TABLE cloud_sessions ADD awaiting_event_seq BIGINT NULL;
ALTER TABLE cloud_sessions ADD awaiting_epoch INT NULL;
