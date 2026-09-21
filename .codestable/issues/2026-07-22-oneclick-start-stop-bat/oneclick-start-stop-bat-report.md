---
doc_type: issue-report
issue: 2026-07-22-oneclick-start-stop-bat
status: confirmed
issue_path: fast-track
severity: P1
summary: 一键启动.bat 调用 start-services.ps1 时因参数名 $home 与 PowerShell 只读变量冲突而失败
tags: [bat, powershell, local-run, start-stop]
---

# 一键启动/停止 bat 异常 Issue Report

## 1. 问题现象

双击或命令行执行 `一键启动.bat` / `start.bat` 时，启动脚本在解析 JDK 路径阶段报错并中止，前后端服务起不来。

实测错误输出：

```text
Cannot overwrite variable home because it is read-only or constant.
At D:\Codeg 部署\get_jobs\bin\start-services.ps1:68 char:9
+     if (Test-IsJdk21 $h) {
+         ~~~~~~~~~~~~~~~
    + CategoryInfo          : WriteError: (home:String) [], ParentContainsErrorRecordException
    + FullyQualifiedErrorId : VariableNotWritable
```

`一键停止.bat` / `stop.bat` 在当前环境可正常执行并退出 0。

另外，根目录 bat（`start.bat` / `stop.bat` / `一键启动.bat` / `一键停止.bat` / `bin/kill-services.bat`）行尾大量为 LF，仅末行混有 CRLF；Windows `cmd` 对纯 LF bat 存在兼容风险。

## 2. 复现步骤

1. 在仓库根目录双击 `一键启动.bat`，或执行：
   `powershell -NoProfile -ExecutionPolicy Bypass -File bin\start-services.ps1 -NoBrowser`
2. 观察控制台输出
3. 观察到：出现 `Cannot overwrite variable home...`，启动中断

复现频率：稳定

## 3. 期望 vs 实际

**期望行为**：一键启动能完成 JDK 探测、停旧进程、拉起前端/后端，并打开管理页。

**实际行为**：启动脚本在 JDK 探测循环中因变量写入失败而中断，服务未启动。

## 4. 环境信息

- 涉及模块 / 功能：本地一键启停脚本（`bin/start-services.ps1`、`bin/stop-services.ps1`、根目录 bat）
- 相关文件 / 函数：
  - `bin/start-services.ps1:28` `function Test-IsJdk21([string]$home)`
  - `bin/start-services.ps1:68` `if (Test-IsJdk21 $h)`
  - `一键启动.bat` / `start.bat` / `一键停止.bat` / `stop.bat`
- 运行环境：dev / Windows，仓库路径含中文与空格（`D:\Codeg 部署\get_jobs`）
- 其他上下文：`JAVA_HOME` 当前为 JDK 17，脚本会尝试定位 JDK 21；`pnpm`/`java` 均可用

## 5. 严重程度

**P1** — 本地开发入口失效，阻塞日常一键启动；停止脚本本身可用，但启动不可用。

## 备注

本地验证：

- `一键停止.bat`：成功（exit 0）
- `start-services.ps1 -NoBrowser`：稳定复现 `$home` 只读冲突

owner 已批准快速通道（ConfirmFixPlan / A）。
