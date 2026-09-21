---
doc_type: feature-ff-note
feature: 51job-cancellable-lock
date: 2026-08-07
requirement: 51job共享浏览器锁等待可取消
tags: [51job, delivery, playwright, lock, cancellation]
---

## 做了什么
- 51job等待共享 Playwright 资源时显示明确等待状态。
- 停止请求可以取消尚未取得浏览器锁的任务，并完成状态收尾。
- 页面根据任务状态加快停止阶段轮询，按钮随任务结束恢复。

## 改了哪些
- `PlaywrightManager` 增加带取消检查的共享锁等待接口，保留原有阻塞式接口。
- `Job51JobService` 增加任务状态字段和等待、停止、取消转换。
- 51job页面读取 `taskState`，等待和停止阶段使用更短轮询间隔。
- 增加共享锁和Job51排队取消回归测试。

## 怎么验证
- 定向 Java 测试、全量 Java 测试均通过。
- 51job 页面 ESLint 通过，0 error，保留 8 个既有 warning。
- 真实接口启动后立即停止最终返回 `isRunning=false、taskState=CANCELLED`。
- 隔离回滚恢复4个既有文件哈希并移除新增测试文件。

## 对 `codestable/` 的影响
- 留下本轮快改记录；项目稳定规格保持原样。
