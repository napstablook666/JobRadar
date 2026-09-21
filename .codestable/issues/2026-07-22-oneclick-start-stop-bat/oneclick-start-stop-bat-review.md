---
doc_type: issue-review
issue: 2026-07-22-oneclick-start-stop-bat
status: passed
reviewer: self-inline-fastpath
scope: bin/start-services.ps1, bin/stop-services.ps1, start.bat, stop.bat, 一键启动.bat, 一键停止.bat, bin/kill-services.bat
---

# 一键启动/停止 bat 异常 Code Review

## 审查范围

快速通道修复 diff，对照 fix-note 根因与验证结果。

## 结论

**Passed** — 无 blocking；无 important 未处理项。改动紧贴根因，验证覆盖了原故障路径。

## Findings

### Blocking

无

### Important

无

### Minor / Nit

1. `stop-services.ps1` 只保护 `$PID` 与直接父 PID；若未来改为 `Start-Process` 异步调 stop，保护链可能不够。当前是同进程 `&` 调用，可接受。
2. 完整后端 cold start 未作为强制验收；若后续常卡在 bootRun，可另开 issue 看日志/端口就绪策略。

## 对照 fix-note

| 项 | 是否满足 |
|---|---|
| `$home` 只读冲突消除 | 是（`$jdkHome`） |
| `java -version` stderr 不致终止 | 是（`Get-JavaVersionText`） |
| start 调 stop 不再自杀 | 是（去掉 `start-services.ps1` 匹配 + PID 保护） |
| bat CRLF | 是 |
| 验证证据 | 是（前端就绪 + 停止 exit 0） |

## 风险

低。仅本地运维脚本，不改业务代码。
