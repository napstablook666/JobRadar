---
doc_type: approval-report
unit: 2026-07-22-oneclick-start-stop-bat
status: approved
reason: review-authorization
approvals:
  fast-path: approved
  fix-completion: approved
approval_groups: {}
created_at: 2026-07-22
---

# Approval Report

## Decision History

- 2026-07-22：owner 批准快速通道（ConfirmFixPlan / A）→ `fast-path: approved`
- 2026-07-22：修复落地，code review **passed**
- 2026-07-22：owner 指示收尾 → `fix-completion: approved`，issue 闭环

## Decision Needed

无（已关闭）

## Why Now

收尾确认。

## Context

### 已改文件

- `bin/start-services.ps1`：`$home` → `$jdkHome`；`Get-JavaVersionText` 安全读 `java -version`
- `bin/stop-services.ps1`：不再误杀 start 进程
- 根目录 / `bin` bat：统一 CRLF

### 验证摘要

- 启动：`[OK] JAVA_HOME=...jdk-21...`，`[OK] frontend ready (~9s)`
- 停止：`一键停止.bat` exit 0，端口释放
- review：`oneclick-start-stop-bat-review.md` status=passed

## Options

已完成，无待选项。

## Recommendation

issue 关闭。

## Risks And Tradeoffs

- 后端完整 cold start 未强制等到 8888 ready；若 bootRun 仍慢可另开 issue。
- 停止脚本仍会强杀占用 6866/8888/7866 的监听进程。

## Non-Automatic Actions

不会自动 commit / push。若要入库，由 owner 单独指示。

## After You Answer

已收尾闭环。
