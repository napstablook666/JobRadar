---
doc_type: feature-ff-note
feature: liepin-pending-cleanup-runtime
date: 2026-08-07
requirement: 修复猎聘清除未投递失败并实操验证
tags: [liepin, cleanup, retry-queue, runtime]
---

## 做了什么

- 将未投递岗位关联的暂缓重试记录纳入同一清理事务，避免删除岗位后留下失效队列。
- 重载 8888 后端到当前源码，恢复暂缓统计接口。

## 改了哪些

- `LiepinService.clearPendingSnapshots()` 先删除未投递岗位的 `liepin_retry_queue` 记录，再删除岗位数据。
- `LiepinServicePendingCleanupTest` 覆盖关联暂缓队列被清除、已投递岗位和分页断点保留。

## 怎么验证

- 定向 Gradle 测试通过。
- 暂缓统计接口由 404 恢复为 200。
- 实际 DELETE 清除 2120 条未投递岗位；复查保留 30 条已投递岗位，未投递和暂缓统计均为 0。
- 浏览器“投递分析”页面显示“清除未投递（0）”，控制台错误列表为空。

## 对 `.codestable/` 的影响

- 新增本次快改记录，不改变现有规格或历史事项。
