---
kind: issue
title: "BAT 根入口可见化适配"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# BAT 根入口可见化适配

## 做了什么

修复根目录三个 BAT 双击后反馈不可见、窗口快速关闭和调用链退出码不清晰的问题。启动、停止、重启现在都会显示执行目录、服务日志和最终结果。

## 改了哪些

- start.bat — 显式定位 Windows PowerShell，保留参数转发并显示失败退出码。
- stop.bat — 与启动入口统一路径、日志和失败反馈。
- restart.bat — 保留 stop -Quiet -> start 顺序，支持直接双击保留窗口、被脚本调用时无暂停。
- 进度白板.md — 记录本轮适配与回归结果。
- .codestable/features/2026-08-09-bat-entrypoint-repair/ — 保存基线、补丁、验证记录和回滚脚本。

## 怎么验证的

真实执行 stop.bat -Quiet、start.bat -NoBrowser、restart.bat -NoBrowser 均返回 0；前端首页和后端 /api/health 返回 200，端口、CRLF、哈希、git diff --check、隔离补丁回放与回滚均通过。

## 对 codestable/ 的影响

- 新增 .codestable/features/2026-08-09-bat-entrypoint-repair/，保存本轮可复现证据。
- 未改变项目规格、API、数据库或平台业务契约。
