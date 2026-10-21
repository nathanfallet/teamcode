CREATE TABLE IF NOT EXISTS team_events (team_id VARCHAR(36), seq BIGINT, `type` VARCHAR(40) NOT NULL, subject_id VARCHAR(36) NOT NULL, owner_id VARCHAR(36) NOT NULL, `at` BIGINT NOT NULL, `data` text NOT NULL, CONSTRAINT pk_team_events PRIMARY KEY (team_id, seq));
CREATE INDEX team_event_owner ON team_events (team_id, owner_id, seq);
CREATE INDEX team_event_retention ON team_events (`at`);
