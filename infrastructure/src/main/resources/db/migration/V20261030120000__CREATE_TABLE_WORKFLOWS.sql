CREATE TABLE IF NOT EXISTS workflows (id VARCHAR(36) PRIMARY KEY, team_id VARCHAR(36) NOT NULL, name VARCHAR(120) NOT NULL, version INT NOT NULL, definition MEDIUMTEXT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, updated_by_id VARCHAR(36) NULL);
CREATE INDEX workflow_team ON workflows (team_id, id);
ALTER TABLE workflows ADD CONSTRAINT workflow_name UNIQUE (team_id, name);
CREATE TABLE IF NOT EXISTS workflow_settings (team_id VARCHAR(36) PRIMARY KEY, editor_mode VARCHAR(8) NOT NULL, editor_ids text NOT NULL);
