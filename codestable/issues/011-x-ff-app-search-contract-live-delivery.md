---
kind: issue
title: "APP 搜索契约真实投递复测"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# APP 搜索契约真实投递复测

## 做了什么

在搜索契约修复和服务重载后，用隔离运行时完成真实小批量投递，确认搜索、薪资确认、翻页、AI 话术、聊天确认和简历发送链路能够闭环。

## 改了哪些

- `.codestable/features/2026-08-09-app-search-parser/` — 保留基线、补丁、验证记录和回滚脚本，并补充本轮真实运行证据。
- `进度白板.md` — 记录本轮成功数、任务收尾和后台登录残留。

## 怎么验证的

- Java 全量回归：`BUILD SUCCESSFUL`，`198` tests，`0` failures，`0` errors，`0` skipped，退出码 `0`。
- 实际任务：`runId=1`，扫描 `9` 个岗位，薪资放行 `9` 个，确认聊天成功 `5` 个；其中 `4` 个确认 AI 话术和简历均已发送，第 `5` 个在停止收尾时仅完成 AI 话术。
- AI 统计：`passed=5`、`aiTimeouts=0`、`buttonFailures=0`、`confirmationFailures=0`。
- 收尾状态：普通任务和暂缓重试均为空闲，健康接口返回 `200`；后台模式下 Cookie 状态仍提示 `needsReimport=true`，后续新任务需先完成可见登录捕获。

## 对 codestable/ 的影响

- 新增本条真实运行 `ff` 记录。
- 既有搜索契约 feature 的验证记录补充了运行态证据；未改变数据库结构和外部 API 契约。
