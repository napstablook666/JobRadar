-- 猎聘 AI 筛选自动投递配置列。
-- 应用启动时会以同样语句尝试迁移，已存在的列会被忽略。
ALTER TABLE liepin_config ADD COLUMN auto_ai_delivery INTEGER DEFAULT 0;
ALTER TABLE liepin_config ADD COLUMN ai_min_score INTEGER DEFAULT 70;
