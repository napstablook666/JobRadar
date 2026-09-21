---
doc_type: issue-fix-note
issue: 2026-08-06-liepin-clear-pending
date: 2026-08-06
status: fixed
tags: [liepin, analytics, delivery, cleanup]
---

# 清理记录

## 已完成

- 以当前数据库文件建立基线副本并完成 SHA-256 校验。
- 在事务中删除 3397 条未投递历史记录。
- 保留 21 条已投递记录及其岗位 ID 集合。
- 保留 1 条分页断点。
- 重启服务并通过统计、列表和状态接口验证。

## 当前影响

本次只处理历史数据；后续新扫描岗位仍按现有逻辑保存为未投递，只有聊天窗口确认成功后才转为已投递。
