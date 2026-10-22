ALTER TABLE artifact_blobs ADD collecting_since BIGINT NULL;
CREATE INDEX artifact_blobs_collecting ON artifact_blobs (collecting_since);