ALTER TABLE artifacts ADD expires_at BIGINT NULL;
CREATE INDEX artifacts_expiry ON artifacts (expires_at);