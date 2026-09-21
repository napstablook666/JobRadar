---
doc_type: issue-analysis
issue: liepin-delivery-interval
status: fixed
tags: [liepin, rate-guard, root-cause]
---

# 根因分析

## 观察

`LiepinRateGuard.waitFor()` 原先在睡眠循环结束后才发送“等待 N 秒后继续”消息；当 `remaining <= 0` 时仍发送同一文案。

## 根因

等待执行和等待状态展示共用一个结束点，且没有单独的“无需额外等待”分支，导致页面提示落后于真实动作并产生误导。

## 边界

账户级共享时钟、250ms 分片停止检查、发送计数和第 5 次批次冷却继续沿用；本次只调整状态消息和对应测试。
