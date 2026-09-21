---
kind: issue
title: "猎聘静默投递回退人工确认"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# 猎聘静默投递回退人工确认

## 做了什么

将失效的 `BATCH_AUTO` 静默投递回退到此前的 `MANUAL` 人工确认路径，保留现有搜索、登录、AI 话术和发送确认实现。

## 改了哪些

- 猎聘运行配置 — `aiDeliveryMode=MANUAL`、`autoAiDelivery=0`。
- `进度白板.md` — 记录回退点和运行态结果。

## 怎么验证的

先停止原 `BATCH_AUTO` 运行轮次，终态为 `PAUSED`、成功数为 0；配置接口回读为 `MANUAL`，服务无运行任务。

## 对 codestable/ 的影响

- 无已记录真相受影响；这是运行配置回退，不改源码和数据结构。
