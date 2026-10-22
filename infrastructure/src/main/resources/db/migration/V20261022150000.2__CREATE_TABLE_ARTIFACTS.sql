CREATE TABLE IF NOT EXISTS artifacts (id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL, owner_id VARCHAR(36) NOT NULL, created_by VARCHAR(36) NOT NULL, credential_id VARCHAR(36) NULL, origin_kind VARCHAR(16) NOT NULL, origin_run_id VARCHAR(36) NULL, origin_session_id VARCHAR(36) NULL, origin_epoch INT NULL, title VARCHAR(200) NOT NULL, description VARCHAR(300) NOT NULL, icon VARCHAR(40) NOT NULL, access VARCHAR(16) NOT NULL, public_follows_latest BOOLEAN NOT NULL, public_version INT NULL, current_version INT NOT NULL, `state` VARCHAR(16) NOT NULL, stored_bytes BIGINT NOT NULL, generation BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, published_at BIGINT NULL, deleted_at BIGINT NULL, storage_deleted_at BIGINT NULL);
CREATE INDEX artifacts_owner ON artifacts (team_id, owner_id, updated_at);
CREATE INDEX artifacts_team ON artifacts (team_id, `state`, updated_at);
CREATE INDEX artifacts_session ON artifacts (origin_session_id);
CREATE INDEX artifacts_run ON artifacts (origin_run_id);
CREATE INDEX artifacts_state ON artifacts (`state`, updated_at);
