ALTER TABLE conversation_context_summaries
    DROP FOREIGN KEY fk_context_summary_session;

ALTER TABLE conversation_context_summaries
    ADD CONSTRAINT fk_context_summary_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE;

ALTER TABLE conversation_context_jobs
    DROP FOREIGN KEY fk_context_job_session;

ALTER TABLE conversation_context_jobs
    ADD CONSTRAINT fk_context_job_session
        FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE;
