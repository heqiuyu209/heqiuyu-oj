-- Existing source/import schema is required. Additive, repeatable; no statement body is rewritten.
SET @statement_refresh_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='remote_problem_import' AND column_name='auto_refresh')=0,
 'ALTER TABLE remote_problem_import ADD COLUMN auto_refresh TINYINT(1) NULL DEFAULT NULL', 'SELECT 1');
PREPARE statement_refresh_migration FROM @statement_refresh_ddl;
EXECUTE statement_refresh_migration;
DEALLOCATE PREPARE statement_refresh_migration;
SET @statement_refresh_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='remote_problem_import' AND column_name='last_checked_at')=0,
 'ALTER TABLE remote_problem_import ADD COLUMN last_checked_at DATETIME(3) NULL', 'SELECT 1');
PREPARE statement_refresh_migration FROM @statement_refresh_ddl;
EXECUTE statement_refresh_migration;
DEALLOCATE PREPARE statement_refresh_migration;
SET @statement_refresh_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='remote_problem_import' AND column_name='last_success_at')=0,
 'ALTER TABLE remote_problem_import ADD COLUMN last_success_at DATETIME(3) NULL', 'SELECT 1');
PREPARE statement_refresh_migration FROM @statement_refresh_ddl;
EXECUTE statement_refresh_migration;
DEALLOCATE PREPARE statement_refresh_migration;
UPDATE remote_problem_import i JOIN remote_statement_current c ON c.problem_key=i.problem_key JOIN remote_statement_version v ON v.id=c.version_id
 SET i.auto_refresh=CASE WHEN v.origin='SOURCE' THEN 1 ELSE 0 END,
 i.next_run_at=CASE WHEN v.origin='SOURCE' AND i.state='DONE' THEN DATE_ADD(v.created_at,INTERVAL 1 DAY) ELSE i.next_run_at END
 WHERE i.auto_refresh IS NULL;
