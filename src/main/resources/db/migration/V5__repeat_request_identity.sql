ALTER TABLE workout_sessions ADD COLUMN source_session_id BIGINT REFERENCES workout_sessions(id);
