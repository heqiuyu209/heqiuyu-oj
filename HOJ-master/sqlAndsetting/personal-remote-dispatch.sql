-- Apply after personal-remote-tasks.sql, before starting the personal worker.
-- Existing rows remain nullable and fail closed if their source scope was not pinned.
SET @remote_dispatch_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='personal_remote_task' AND column_name='request_hash')=0,
 'ALTER TABLE personal_remote_task ADD COLUMN request_hash CHAR(64) NULL AFTER request_id', 'SELECT 1');
PREPARE remote_dispatch_statement FROM @remote_dispatch_ddl;
EXECUTE remote_dispatch_statement;
DEALLOCATE PREPARE remote_dispatch_statement;
SET @remote_dispatch_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='personal_remote_task' AND column_name='source_contest')=0,
 'ALTER TABLE personal_remote_task ADD COLUMN source_contest VARCHAR(80) NULL AFTER remote_problem', 'SELECT 1');
PREPARE remote_dispatch_statement FROM @remote_dispatch_ddl;
EXECUTE remote_dispatch_statement;
DEALLOCATE PREPARE remote_dispatch_statement;
