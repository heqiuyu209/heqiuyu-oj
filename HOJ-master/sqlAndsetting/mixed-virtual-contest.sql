-- Apply after paper.sql, mixed-contest.sql and virtual-contests.sql. Additive and repeatable.
SET @mixed_virtual_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='paper_run' AND column_name='virtual_run_id')=0,
 'ALTER TABLE paper_run ADD COLUMN virtual_run_id BIGINT UNSIGNED NULL', 'SELECT 1');
PREPARE mixed_virtual_migration FROM @mixed_virtual_ddl;
EXECUTE mixed_virtual_migration;
DEALLOCATE PREPARE mixed_virtual_migration;
SET @mixed_virtual_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='paper_run' AND index_name='uk_paper_run_virtual')=0,
 'ALTER TABLE paper_run ADD UNIQUE KEY uk_paper_run_virtual (virtual_run_id)', 'SELECT 1');
PREPARE mixed_virtual_migration FROM @mixed_virtual_ddl;
EXECUTE mixed_virtual_migration;
DEALLOCATE PREPARE mixed_virtual_migration;
