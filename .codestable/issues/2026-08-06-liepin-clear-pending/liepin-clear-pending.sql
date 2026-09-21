-- 一次性清理岗位统计中的未投递历史记录。
-- 仅保留 delivered=1；空值按未投递处理。
BEGIN IMMEDIATE;
DELETE FROM liepin_data
WHERE delivered = 0 OR delivered IS NULL;
COMMIT;
