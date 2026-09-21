---
doc_type: issue-fix-note
issue: 2026-07-22-oneclick-start-stop-bat
status: confirmed
---

# 一键启动/停止 bat 异常 Fix Note

## 1. 根因

1. **主因**：`bin/start-services.ps1` 中 `Test-IsJdk21([string]$home)` 使用了 PowerShell 只读自动变量名 `$home`。调用时参数绑定尝试覆写 `$home`，在 `$ErrorActionPreference = "Stop"` 下直接终止：
   `Cannot overwrite variable home because it is read-only or constant.`

2. **连带阻塞**：`java -version` 写到 stderr。在 `$ErrorActionPreference = "Stop"` 下，`& java -version 2>&1` 被当作 terminating error，导致 JDK 探测即使改了参数名仍失败。

3. **连带阻塞**：`bin/stop-services.ps1` 会按命令行匹配杀掉含 `start-services.ps1` 的进程。而启动脚本第一步会调用 stop，因此会自杀，表现为停在 `[1/4] Stop old processes...` 后异常退出。

4. **兼容问题**：根目录 bat 行尾多为 LF，Windows `cmd` 下存在兼容风险。

## 2. 改动

| 文件 | 改动 |
|---|---|
| `bin/start-services.ps1` | `$home` → `$jdkHome`；新增 `Get-JavaVersionText`，在 `Continue` 下安全读取 `java -version` |
| `bin/stop-services.ps1` | 窗口清理不再匹配 `start-services.ps1`；保护自身/父进程 PID |
| `start.bat` / `stop.bat` / `一键启动.bat` / `一键停止.bat` / `bin/kill-services.bat` | 统一 CRLF 行尾 |

## 3. 验证

| 步骤 | 结果 |
|---|---|
| `start-services.ps1 -NoBrowser` 越过 JDK 探测 | 通过：`[OK] JAVA_HOME=...jdk-21...` |
| 启动前端 readiness | 通过：`[OK] frontend ready (~9s)`，`localhost:6866` 可连 |
| 启动链路不再自杀于 stop | 通过：能进入 `[2/4] Start frontend` / `[3/4] Start backend` |
| `一键停止.bat` | 通过：exit 0，端口释放 |
| bat 行尾 | 通过：相关 bat/ps1 均为纯 CRLF |

说明：完整后端 `bootRun` 就绪可能超过 90s，本次验证以越过原故障点 + 前端就绪 + 停止可用为准；未把完整后端冷启动时长作为本 issue 阻塞项。

## 4. 遗留风险

- 路径含中文/空格时，若外部用错误引号包装 `-File` 路径，仍会失败（调用方问题，非脚本逻辑）。
- `stop-services.ps1` 仍会按端口强杀监听进程；若本机其他程序占用 6866/8888/7866，也会被杀掉。
- `JAVA_HOME` 指向非 21 时脚本会忽略并改用探测到的 JDK 21，符合预期。

## 5. 顺手发现（未改）

无。
