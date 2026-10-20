ALTER TABLE reservations ADD run_id VARCHAR(36) NULL;
CREATE INDEX run_active ON reservations (run_id, `state`, expires_at);
ALTER TABLE reservations ADD CONSTRAINT fk_reservations_run_id__id FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON UPDATE RESTRICT;
