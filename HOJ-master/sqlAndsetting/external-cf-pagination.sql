-- Additive upgrade for existing external-learning databases. Safe to repeat.
SET @hoj_cf_head_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='external_sync_job' AND column_name='cf_head_hash');
SET @hoj_cf_head_sql = IF(@hoj_cf_head_exists=0, 'ALTER TABLE external_sync_job ADD COLUMN cf_head_hash CHAR(64) NULL', 'SELECT 1');
PREPARE hoj_cf_head_upgrade FROM @hoj_cf_head_sql;
EXECUTE hoj_cf_head_upgrade;
DEALLOCATE PREPARE hoj_cf_head_upgrade;
