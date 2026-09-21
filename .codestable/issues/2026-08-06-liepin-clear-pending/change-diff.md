# 变更差异

## 数据变更

- 目标：`db/getjobs.db` 的 `liepin_data` 表。
- 清理条件：`delivered = 0 OR delivered IS NULL`。
- 保留条件：`delivered = 1`。
- SQL 补丁：`liepin-clear-pending.sql`。

## 代码与接口

本次没有源码、API 或筛选逻辑变更；统计接口继续读取岗位数据表，分页断点继续保留。
