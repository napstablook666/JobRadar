# 未投递岗位清理验证记录

## 基线

- 当前数据库：`db/getjobs.db`
- 基线副本：`baseline/getjobs.db`
- 基线 SHA-256：`CBB195DE7C0325CD01852CABFD16D87D3008B7BAD044DF5348D4E0AA9246035D`
- 清理完成、服务重启前 SHA-256：`157293BF9CAA87F540C5EE11BC5B925BB19A789BDD2DF02830FED787B2122FA3`
- 当前运行态 SHA-256：`088586811271B4BBC7AF877B55E9FA35E651B3AEBD3391BDDF145F6196AB1379`

## 命令、字面输出与退出码

| 步骤 | 命令摘要 | 字面输出摘要 | 退出码 |
|---|---|---|---:|
| 基线复制 | `Copy-Item db/getjobs.db baseline/getjobs.db; Get-FileHash` | `BASELINE_HASH_MATCH=1` | 0 |
| 数据清理 | SQLite 事务执行 `DELETE FROM liepin_data WHERE delivered=0 OR delivered IS NULL` | `DELETED_ROWS=3397`；`INTEGRITY_CHECK=ok` | 0 |
| 清理校验 | SQLite 计数与已投递 ID SHA-256 比对 | `AFTER_TOTAL=21`；`AFTER_DELIVERED=21`；`AFTER_PENDING=0`；`DELIVERED_IDS_PRESERVED=1`；`PAGE_PROGRESS_PRESERVED=1` | 0 |
| SQL 补丁隔离应用 | 基线副本执行 `liepin-clear-pending.sql` | `PATCH_TOTAL=21`；`PATCH_DELIVERED=21`；`PATCH_PENDING=0`；`PATCH_FUNCTIONAL_MATCH=1` | 0 |
| 隔离回滚 | `powershell -File rollback-liepin-clear-pending.ps1` | `ROLLBACK_OK`；恢复后 `TOTAL=3418`、`DELIVERED=21`、`PENDING=3397` | 0 |
| 接口验证 | `GET /api/liepin/stats`、`GET /api/liepin/list`、`GET /api/liepin/status` | `total=21`；`delivered=21`；`pending=0`；无状态列表 `21`；已投递列表 `21`；未投递列表 `0`；`API_VALIDATION=PASS` | 0 |
| 收尾检查 | 只读数据库、端口、接口和 `git diff --check` | `DB_TOTAL=21`；`DB_DELIVERED=21`；`DB_PENDING=0`；`DB_INTEGRITY=ok`；`PORT_8888_LISTEN=1`；`PORT_6866_LISTEN=1`；`GIT_DIFF_CHECK=0`；`FINAL_VALIDATION=PASS` | 0 |

## 已验证行为

1. 已投递岗位总数和 ID 集合前后一致。
2. 未投递历史记录清零，统计图表只剩已投递数据。
3. 分页断点保持 1 条。
4. 回滚脚本可在隔离副本恢复清理前数据库。

服务重启会刷新 SQLite 文件布局，因此补丁验证以表内容、已投递 ID 和分页断点做等价比对；当前运行态接口和完整性检查均通过。
