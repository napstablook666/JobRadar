---
doc_type: feature-ff-note
feature: liepin-clear-pending-action
date: 2026-08-06
requirement: 猎聘分析页一键清除未投递岗位，保留已投递岗位避免重复投递
tags: [liepin, analytics, delivery, cleanup]
---

## 做了什么
- 分析页读取不受筛选条件影响的岗位状态摘要，展示待清理数量和已投递保留数量。
- 增加二次确认的“清除未投递”操作，只删除 `delivered=0` 或 `delivered IS NULL`。
- 投递任务运行时按钮禁用，后端返回 HTTP 409，避免清理与投递并发发生。
- 清理成功后刷新统计、列表和全局数量；分页断点和 AI 缓存表不参与删除。

## 改了哪些
- `LiepinService`：新增事务清理和全局摘要查询。
- `LiepinController`：新增 `GET /api/liepin/pending-summary` 与 `DELETE /api/liepin/pending`。
- `AnalysisContent.tsx`、`liepin/page.tsx`：新增数量展示、确认弹窗、运行态禁用和刷新。
- 新增服务层、控制器回归测试；同步 `进度白板.md`。

## 回滚口径
- 使用 `rollback-liepin-clear-pending-action.ps1`，按 feature 标记移除代码并删除新增测试。
- 回滚只针对本 feature 标记和调用点，不覆盖工作区其他既有改动。
