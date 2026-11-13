ALTER TABLE skills ADD owner_id VARCHAR(36) NULL;
CREATE INDEX skill_team_owner ON skills (team_id, owner_id);
