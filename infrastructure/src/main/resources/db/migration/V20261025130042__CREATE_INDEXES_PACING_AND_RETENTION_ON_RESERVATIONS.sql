CREATE INDEX reservation_retention ON reservations (day_number, `state`);
CREATE INDEX member_recent ON reservations (member_id, created_at, units);
CREATE INDEX member_session_recent ON reservations (member_id, session_key, created_at, units);