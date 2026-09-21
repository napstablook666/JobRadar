---
doc_type: feature-ff-note
feature: job51-ai-greeting
date: 2026-08-06
requirement: 51job 加入 AI 生成打招呼并自动发送
tags: [51job, ai, greeting, delivery]
---

## 做了什么
- 保留默认批量投递行为；`enableAi=0` 时不进入逐岗位 AI 流程。
- `enableAi=1` 时按岗位读取 JD，调用既有 AI 配置生成打招呼语，并在岗位详情页自动发送。
- 对缺少岗位快照、详情链接、JD、AI 配置、空响应、`false` 响应和请求异常的岗位记录跳过状态，不发送平台默认语。
- 只有聊天区出现新增本人消息或明确成功提示才记录 `SENT`；同时保存 JD、状态、失败原因和时间。
- 增加单次最多 10 个岗位、5-300 秒随机间隔，并让最大间隔始终不小于最小间隔；页面分析表展示 AI 打招呼状态。

## 改了哪些
- `src/main/java/com/getjobs/worker/job51/Job51.java`：逐岗位 AI 流程、JD 提取、文案生成、发送确认、停止与页面恢复。
- `src/main/java/com/getjobs/worker/job51/Job51Config.java`：AI 默认值和边界收敛。
- `src/main/java/com/getjobs/worker/job51/Job51Locators.java`：岗位详情、沟通输入、发送和成功提示定位候选。
- `src/main/java/com/getjobs/worker/service/Job51JobService.java`：任务状态和 AI 计数回传。
- `src/main/java/com/getjobs/application/service/Job51Service.java`、`Job51ConfigEntity.java`、`Job51Entity.java`：配置字段、数据库列、JD 与打招呼状态持久化。
- `front/app/51job/page.tsx`、`front/app/51job/analysis/AnalysisContent.tsx`：AI 配置、间隔约束和状态展示。
- `src/test/java/com/getjobs/worker/job51/Job51BehaviorTest.java`：AI 响应与间隔边界回归测试。

## 验证结论
- Java 全量测试通过。
- 51job 页面定向 ESLint 为 0 errors；Next.js 生产构建通过。
- `git diff --check` 通过。
- 运行态配置确认 AI 关闭、上限 10、间隔 5-10 秒，任务空闲。

## 回滚口径
- `rollback-job51-ai-greeting.ps1` 停止正在运行的 51job 任务，并保留关键词、城市和薪资，仅恢复 AI 关闭及默认限制。
- 代码变更对应 `job51-ai-greeting.patch`；需要恢复代码时对该 patch 执行反向应用，并单独复跑验证命令。
