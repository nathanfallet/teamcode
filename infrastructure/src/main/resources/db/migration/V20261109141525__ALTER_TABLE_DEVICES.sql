ALTER TABLE devices ADD push_token VARCHAR(512) NULL;
ALTER TABLE devices ADD push_public_key VARCHAR(255) NULL;
ALTER TABLE devices ADD push_token_updated_at BIGINT NULL;