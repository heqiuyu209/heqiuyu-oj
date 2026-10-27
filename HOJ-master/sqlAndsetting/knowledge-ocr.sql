-- Existing installations only. Fresh installs use knowledge-base.sql.
-- Additive/repeatable; no source bytes, versions or publication pointers change.
SET @hoj_ocr_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_source' AND COLUMN_NAME='extraction_mode')=0,
  'ALTER TABLE knowledge_source ADD COLUMN extraction_mode varchar(16) NOT NULL DEFAULT ''NATIVE''',
  'SELECT 1');
PREPARE hoj_ocr_stmt FROM @hoj_ocr_ddl;
EXECUTE hoj_ocr_stmt;
DEALLOCATE PREPARE hoj_ocr_stmt;
