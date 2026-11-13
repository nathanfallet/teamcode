ALTER TABLE memories ADD proposal_wait_id VARCHAR(36) NULL;
CREATE INDEX memory_status ON memories (`status`, updated_at);
