ALTER TABLE members ADD is_owner BOOLEAN DEFAULT FALSE NOT NULL;
CREATE INDEX team_owners ON members (team_id, is_owner, disabled, must_change_password);