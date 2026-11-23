-- Apply personal-remote-tasks.sql first. No cookies, access tokens or source code belong here.
CREATE TABLE IF NOT EXISTS personal_remote_nowcoder (
 submit_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 context_json TEXT NOT NULL,
 context_hash CHAR(64) NOT NULL,
 backend_job_id VARCHAR(80) NULL,
 source_submission_id BIGINT NULL,
 CONSTRAINT fk_personal_nowcoder_task FOREIGN KEY(submit_id) REFERENCES personal_remote_task(submit_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
