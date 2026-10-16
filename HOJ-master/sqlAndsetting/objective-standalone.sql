-- Upgrade objective tables created before standalone practice was introduced.
-- Apply once to an existing database. Fresh installations use objective.sql.
-- Existing questions become available for standalone practice; no content is changed.
ALTER TABLE objective_question
    ADD COLUMN practice_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER published;

ALTER TABLE objective_run
    MODIFY COLUMN set_id BIGINT NULL,
    MODIFY COLUMN set_version INT NULL,
    ADD COLUMN question_id BIGINT NULL AFTER set_version,
    ADD KEY idx_objective_run_question (question_id);
