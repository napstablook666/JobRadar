---
doc_type: issue-report
issue: 2026-08-06-liepin-target-closed
status: confirmed
issue_path: fastforward
severity: P1
summary: 投递过程中共享浏览器页面关闭后，任务直接显示原始 TargetClosedError
tags: [liepin, delivery, playwright, lifecycle]
---

# 投递页面生命周期异常 Issue Report

## 1. 问题现象

投递任务执行期间出现：

`投递失败: Error { message='Target page, context or browser has been closed ... }`

页面关闭、浏览器重连或 CDP 目标失效后，任务直接进入失败状态，前端收到的消息包含底层 Playwright 异常文本。

## 2. 复现路径

1. 启动投递任务。
2. 在搜索导航、岗位详情读取或聊天确认期间关闭投递标签页，或让浏览器目标断开。
3. 观察任务状态。

## 3. 期望行为

- 页面副作用开始前，自动恢复页面并重试当前关键词一次。
- 发送已经开始后，停止当前任务并明确提示发送结果需要检查。
- 页码断点保持可继续运行。
- 前端状态显示稳定中文消息，日志保留完整异常堆栈。

