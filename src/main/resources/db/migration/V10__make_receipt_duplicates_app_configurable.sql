SET @receipt_user_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'receipt'
      AND s.INDEX_NAME = 'idx_receipt_user_id'
);

SET @receipt_user_index_sql := IF(
    @receipt_user_index_exists > 0,
    'SELECT 1',
    'CREATE INDEX `idx_receipt_user_id` ON `receipt` (`user_id`)'
);

PREPARE receipt_user_index_stmt FROM @receipt_user_index_sql;
EXECUTE receipt_user_index_stmt;
DEALLOCATE PREPARE receipt_user_index_stmt;

SET @receipt_duplicate_index := (
    SELECT s.INDEX_NAME
    FROM INFORMATION_SCHEMA.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'receipt'
      AND s.NON_UNIQUE = 0
    GROUP BY s.INDEX_NAME
    HAVING GROUP_CONCAT(s.COLUMN_NAME ORDER BY s.SEQ_IN_INDEX) = 'user_id,store_id,recognized_date'
    LIMIT 1
);

SET @receipt_duplicate_sql := IF(
    @receipt_duplicate_index IS NULL,
    'SELECT 1',
    CONCAT('ALTER TABLE `receipt` DROP INDEX `', REPLACE(@receipt_duplicate_index, '`', '``'), '`')
);

PREPARE receipt_duplicate_stmt FROM @receipt_duplicate_sql;
EXECUTE receipt_duplicate_stmt;
DEALLOCATE PREPARE receipt_duplicate_stmt;
