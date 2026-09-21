---
kind: issue
title: "BAT 启动链重新适配"
type: ff
status: closed
created: 2026-08-11
epic: ""
---

## 做了什么

修复前端启动阶段触发 `pnpm/npx` 依赖预检的问题，并让 BAT 启动链真实反映服务就绪状态。

## 改了哪些

- `front/start-dev.mjs` 直接解析并启动本地 Next CLI。
- `bin/start-services.ps1` 生成的前端 helper 直接执行 Node，增加本地依赖检查和子进程退出检测。
- 前后端任一服务未就绪时显示 `Start failed` 并返回退出码 `1`。

## 怎么验证

- `node --check front/start-dev.mjs`、PowerShell AST 解析和 `git diff --check` 通过。
- 从项目目录启动：前端约 7 秒就绪，后端约 12 秒就绪，`START_EXIT=0`。
- 从 `C:\Windows` 启动：`START_FROM_WINDOWS=0 FRONT_HTTP=200 API_HEALTH=200`。
- 停止验证：`STOP_FROM_WINDOWS=0 PORTS_AFTER=False`。
- 生成的 `run-front.bat` 为 CRLF，`HAS_PNPM=False HAS_NPX=False HAS_NODE=True`。

## 对 codestable/ 的影响

新增本次快改记录；项目规格、API 和数据库契约保持不变。
