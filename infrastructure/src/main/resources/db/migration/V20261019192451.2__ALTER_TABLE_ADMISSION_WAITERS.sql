ALTER TABLE admission_waiters ADD run_id VARCHAR(36) NULL;
CREATE INDEX waiter_run ON admission_waiters (run_id, expires_at);
ALTER TABLE admission_waiters ADD CONSTRAINT fk_admission_waiters_run_id__id FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON UPDATE RESTRICT;
