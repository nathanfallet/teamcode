ALTER TABLE session_todos ADD last_hook_at BIGINT NULL;
ALTER TABLE session_todos ADD reminder_seq BIGINT NULL;
CREATE INDEX session_todo_active ON session_todos (status, updated_at);CREATE INDEX cloud_session_running ON cloud_sessions (state, desired_state);
