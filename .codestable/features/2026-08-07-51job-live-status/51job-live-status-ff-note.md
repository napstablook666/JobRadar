---
doc_type: feature-ff-note
feature: 51job-live-status
date: 2026-08-07
requirement: 51job启动反馈与实时运行记录
tags: [51job, delivery, status, sse]
---

## 做了什么
- 51job启动接口先占用任务状态，再异步执行，避免重复点击和页面无反馈。
- 页面接入投递进度 SSE 和状态轮询，显示最新5条运行记录。
- 启动失败、停止请求、任务完成和异常均在页面可见，任务结束后按钮自动恢复。

## 改了哪些
- `src/main/java/com/getjobs/worker/service/Job51JobService.java`：增加5条内存状态历史、状态字段、原子异步启动和统一消息发布。
- `src/main/java/com/getjobs/application/controller/JobController.java`：使用任务服务的原子异步启动入口。
- `front/app/51job/page.tsx`：接入 `/api/51job/stream`、`/api/51job/status`、实时日志区域和启动/停止反馈。
- `src/test/java/com/getjobs/worker/service/Job51JobServiceStatusTest.java`：增加状态历史与初始状态回归测试。

## 怎么验证
- 51job 定向 Java 测试通过：`Job51BehaviorTest`、`Job51JobServiceStatusTest`。
- 51job 页面定向 ESLint 通过，无 error；Next.js 生产构建通过。
- 运行态状态接口返回 `recentMessages`；短启动验证看到初始化、配置、开始投递、停止和最终收尾消息，最终 `isRunning=false`。
- `/api/51job/stream` 实测返回 `connected` 事件。
- `git diff --check` 通过；全量 Java 测试受现有猎聘代码编译错误阻塞。

## 对 `codestable/` 的影响
- 已留下本轮快改记录；没有新增或修改项目规格。
