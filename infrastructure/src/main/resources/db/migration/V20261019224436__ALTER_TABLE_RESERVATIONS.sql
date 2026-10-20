ALTER TABLE reservations ADD source_pool_id VARCHAR(36) NULL;
CREATE INDEX source_pool_daily ON reservations (team_id, source_pool_id, day_number);