ALTER TABLE cloud_session_events ADD feed VARCHAR(128) DEFAULT '' NOT NULL;
CREATE INDEX cloud_event_feed ON cloud_session_events (session_id, feed, seq);