ALTER TABLE admission_waiters ADD turn_pool_id VARCHAR(36) NULL;
CREATE INDEX waiter_turn_scope ON admission_waiters (team_id, turn_pool_id, provider, expires_at);