-- Existing installations: widen the PDF binary column without changing rows.
-- Fresh installs use knowledge-base.sql. Replays do not repeat an ALTER.
SET @hoj_pdf_capacity_ddl = IF(
  (SELECT DATA_TYPE FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='knowledge_source' AND COLUMN_NAME='source_data')='mediumblob',
  'ALTER TABLE knowledge_source MODIFY COLUMN source_data longblob NOT NULL',
  'SELECT 1');
PREPARE hoj_pdf_capacity_stmt FROM @hoj_pdf_capacity_ddl;
EXECUTE hoj_pdf_capacity_stmt;
DEALLOCATE PREPARE hoj_pdf_capacity_stmt;
