# 浏览器后台模式验证记录

## 基线

- 基线哈希：`baseline-hashes.txt`
- 工作区原有的 `PlaywrightManager.java` 改动已保留，未执行覆盖或回退。

实现后哈希：

- `PlaywrightManager.java`: `30B03CFBC44D4A693C1C43C3F7031AFC7E8E1AFFB82F7FD8F85EB37DB669CC23`
- `PlaywrightController.java`: `49909E53B93F091C9F961EC92464B2A124BAA4AF0FAB76EEFACB3906D7C053CE`
- `application.yaml`: `F2C86A1639095DF6120ED371AF092C2F994B482DDC7216B11248F5B3AC756376`
- `bin/start-services.ps1`: `60B93E8870FFE45D7ABB23E72864E6F34CA4489D1600124BB50DCB5A7D3C13E3`
- `PlaywrightManagerModeTest.java`: `718956A1DC5801FC5E9CCE15AF413A4E1B2EBA72C3D36A809835BA99582ACC35`
- `start.bat` / `一键启动.bat`: 已切换到隐藏 PowerShell 启动且由环境变量关闭暂停。
- `start.bat`: `F26354E551ADA55552B0A695561DCAED19F1BFC72F8FC48D2F7446857F108ED2`
- `一键启动.bat`: `F26354E551ADA55552B0A695561DCAED19F1BFC72F8FC48D2F7446857F108ED2`

## 自动化验证

命令：

`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test`

字面结果：

`BUILD SUCCESSFUL in 12s`

命令：PowerShell AST 解析 `bin/start-services.ps1`

字面结果：`PowerShell parse OK`

命令：`git diff --check`（目标 Java、YAML、脚本和测试文件）

字面结果：无错误，仅保留 Git 的 CRLF 提示。

## 运行态观察

- 当前后端仍有投递任务运行，状态接口返回 `isRunning=true`。
- 当前运行态快照：`taskState=RUNNING`, `runId=1`。
- 为保持运行任务连续性，本轮未重启 8888 服务。
- 后台浏览器进程参数和 visible-login 登录流程需在任务自然收尾后验证。

## 预期行为

- 默认 `GETJOBS_BROWSER_MODE=background` 使用 `--headless=new`。
- `GETJOBS_BROWSER_MODE=visible-login` 保留可见扫码登录。
- 默认启动不打开管理页；`-OpenBrowser` 作为显式入口。
