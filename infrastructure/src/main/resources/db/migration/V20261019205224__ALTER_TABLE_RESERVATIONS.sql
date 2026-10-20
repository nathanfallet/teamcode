ALTER TABLE reservations ADD provider_request_id VARCHAR(128) NULL;
CREATE INDEX reconciliation_pending ON reservations (team_id, `state`, created_at, id);