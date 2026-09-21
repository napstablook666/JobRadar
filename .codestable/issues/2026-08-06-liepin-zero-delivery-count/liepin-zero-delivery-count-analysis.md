---
doc_type: issue-analysis
issue: 2026-08-06-liepin-zero-delivery-count
status: fixed
---

# 根因与修复分析

## 根因

`Liepin` 原先使用独立的 `processedCount` 和岗位 ID 集合，在确认发送前就把可投递卡片记入上限。后续流程可能跳过岗位，或发送观察结果为不确定，因此服务层最终拿到的 `resultList.size()` 仍是 0，但展示层沿用成功类型。

## 修复

- 删除预处理计数及其去重状态。
- 统一使用 `resultList.size()` 作为成功聊天数和单次上限依据。
- 仅在 `SendResult.sent()` 为真时追加结果、更新已投递状态并检查上限。
- 将完成消息抽成 `buildCompletionMessage`：正数保持成功类型，0 条使用警告类型和明确文案。
- 增加 0 条与正数两条服务层回归测试。

## 保留行为

AI 相关度闸门、人工确认、限速、分页断点、页面恢复和发送后结果不确定处理均未改变。
