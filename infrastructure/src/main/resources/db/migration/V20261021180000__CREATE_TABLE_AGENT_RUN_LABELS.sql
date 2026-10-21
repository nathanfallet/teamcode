CREATE TABLE IF NOT EXISTS agent_run_labels (run_id VARCHAR(36), label_key VARCHAR(63), label_value VARCHAR(256) NOT NULL, CONSTRAINT pk_agent_run_labels PRIMARY KEY (run_id, label_key), CONSTRAINT agent_run_labels_run FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE);
CREATE INDEX run_label_lookup ON agent_run_labels (label_key, label_value);
