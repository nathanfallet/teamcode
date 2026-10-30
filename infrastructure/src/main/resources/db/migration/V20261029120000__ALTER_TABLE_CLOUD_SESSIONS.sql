CREATE INDEX cloud_session_awaiting_owner ON cloud_sessions (team_id, owner_id, awaiting_since);
CREATE INDEX cloud_session_awaiting_team ON cloud_sessions (team_id, awaiting_since);
