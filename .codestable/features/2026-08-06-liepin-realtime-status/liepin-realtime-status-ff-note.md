---
doc_type: feature-ff-note
feature: liepin-realtime-status
date: 2026-08-06
requirement: 猎聘 AI 筛选展示多行实时运行状态
tags: [liepin, ai, status, polling]
---

## 做了什么
- 后端保留最近 8 条状态消息，状态接口新增 `recentMessages`，保留原有单条字段兼容旧页面。
- 前端展示时间、类型、评分原因、跳过原因、投递结果和进度信息，最新消息自动滚动到列表底部。
- 任务结束后保留本轮记录，新任务开始时清空上一轮记录。

## 验证结论
- Java 全量测试、页面 ESLint、Next.js 生产构建通过。
- 补丁应用检查和回滚校验通过。
- 当前 8888 进程仍在执行旧任务，待任务结束后重载后端即可看到完整消息队列。

## 回滚口径
- 使用 `rollback-liepin-realtime-status.ps1` 恢复本轮涉及的 4 个文件。
