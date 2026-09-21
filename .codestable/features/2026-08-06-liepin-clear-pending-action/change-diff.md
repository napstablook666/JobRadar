# 猎聘清除未投递岗位差异

## 行为差异

- 新增全局摘要接口：`GET /api/liepin/pending-summary`。
- 新增事务清理接口：`DELETE /api/liepin/pending`。
- 清理条件固定为 `delivered = 0 OR delivered IS NULL`。
- `delivered = 1` 岗位保持原记录和岗位 ID 集合。
- 投递任务运行中清理接口返回 `409 Conflict`，空闲时返回删除数量和保留数量。

## 页面差异

- 分析页按钮显示全局待清理数量和已投递保留数量。
- 清理前弹出二次确认，清理后刷新统计、列表和数量。
- 页面通过父页 `isDelivering` 禁用清理操作，后端再次检查运行状态。

## 文件范围

- `src/main/java/com/getjobs/application/service/LiepinService.java`
- `src/main/java/com/getjobs/application/controller/LiepinController.java`
- `front/app/liepin/analysis/AnalysisContent.tsx`
- `front/app/liepin/page.tsx`
- `src/test/java/com/getjobs/application/service/LiepinServicePendingCleanupTest.java`
- `src/test/java/com/getjobs/application/controller/LiepinControllerPendingCleanupTest.java`
- `进度白板.md`
