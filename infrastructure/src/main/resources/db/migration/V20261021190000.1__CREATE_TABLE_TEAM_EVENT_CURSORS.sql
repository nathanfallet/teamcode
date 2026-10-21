CREATE TABLE IF NOT EXISTS team_event_cursors (team_id VARCHAR(36), last_seq BIGINT NOT NULL, CONSTRAINT pk_team_event_cursors PRIMARY KEY (team_id));
