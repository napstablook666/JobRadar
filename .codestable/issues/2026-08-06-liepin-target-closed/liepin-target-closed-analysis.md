---
doc_type: issue-analysis
issue: 2026-08-06-liepin-target-closed
status: confirmed
root_cause_type: missing-guard
related:
  - liepin-target-closed-report.md
tags: [liepin, delivery, playwright, lifecycle]
---

# 投递页面生命周期异常 根因分析

## 1. 代码证据

- `LiepinJobService` 只判断页面引用是否为 `null`，旧 `Page` 关闭后仍会传入投递流程。
- `PlaywrightManager` 没有监听猎聘页面关闭事件，也没有页面健康检查和恢复入口。
- `Liepin` 的搜索、详情、悬停和发送流程存在宽泛异常捕获，页面关闭异常会在不同层级被吞掉或直接冒泡。
- 共享访问锁只保证调用串行，目标存活仍需单独探测。

## 2. 影响

页面失效会终止整次投递任务；聊天点击后发生失效时，重复重试还可能造成重复发送。

## 3. 结论

根因是共享页面生命周期缺少健康检查、重建和副作用边界。修复重点放在管理器恢复接口、一次性关键词重试以及发送后的结果不确定分支。
