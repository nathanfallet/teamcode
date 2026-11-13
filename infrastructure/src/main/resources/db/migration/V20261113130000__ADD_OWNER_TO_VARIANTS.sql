ALTER TABLE variants ADD owner_id VARCHAR(36) DEFAULT '' NOT NULL, DROP INDEX variant_team_slot_name, ADD CONSTRAINT variant_team_slot_name UNIQUE (team_id, kind, target, `name`, owner_id);
CREATE INDEX variant_team_owner ON variants (team_id, owner_id);
