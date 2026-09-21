# 猎聘清除未投递岗位验证记录

## 基线与范围

- 工作区：`D:\Codeg 部署\get_jobs`
- 后端：`http://localhost:8888`
- 前端：`http://localhost:6866`
- 运行中的投递任务在重载前保持原状态，不执行数据库清理。

## 已完成命令与结果

- 猎聘服务层定向测试：通过，退出码 `0`。
- 猎聘控制器与服务测试：通过，退出码 `0`。
- Java 全量测试：`BUILD SUCCESSFUL`，退出码 `0`。
- 猎聘页面定向 ESLint：`0 errors`，保留 4 个既有 warning，退出码 `0`。
- Next.js 生产构建：`Compiled successfully`，退出码 `0`。
- `git diff --check`：空输出，退出码 `0`。
- 隔离回滚：输出 `ROLLBACK_OK`、`ROLLBACK_MARKERS=0`、`ROLLBACK_NEW_TESTS_REMOVED=1`、`ROLLBACK_SHAPE_OK=1`，退出码 `0`。
- 临时新编译后端 `8890` 只读回归：`summary.total=2004`、`summary.delivered=28`、`summary.pending=1976`、`stats/list/pageProgress` 数量一致，退出码 `0`。
- 临时实例清理：`TEMP_BACKEND_PORT_LISTEN=0`、`TEMP_BACKEND_PROCESS_COUNT=0`，退出码 `0`。

## 待运行态收尾

- 主端口 `8888` 当前仍由投递任务占用，尚未重载；新接口已在临时端口完成只读验证。
- 真实 `DELETE /api/liepin/pending` 未调用，因为当前摘要存在 `1976` 条待清理记录；删除行为由内存数据库测试覆盖。
