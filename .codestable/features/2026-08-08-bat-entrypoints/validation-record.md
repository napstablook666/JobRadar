# BAT 运维入口三件套验证记录

## 基线

- start.bat: F26354E551ADA55552B0A695561DCAED19F1BFC72F8FC48D2F7446857F108ED2
- stop.bat: 6E7DFF246BF8418BD6666403A52B54A9709C615A82245BD8E92D6FC2F228D929
- 一键启动.bat: F26354E551ADA55552B0A695561DCAED19F1BFC72F8FC48D2F7446857F108ED2
- 一键停止.bat: 6E7DFF246BF8418BD6666403A52B54A9709C615A82245BD8E92D6FC2F228D929

## 命令与字面结果

1. 根目录 BAT 盘点：退出码 0；输出 BAT_SET=restart.bat,start.bat,stop.bat；SUPPORT_BAT=gradlew.bat,bin/kill-services.bat。
2. 三个 BAT 行尾检查：退出码 0；输出 BAT_EOL=CRLF。
3. PowerShell AST 解析 bin/start-services.ps1、bin/stop-services.ps1：退出码 0；输出 PS_PARSE=0。
4. git diff --check -- bin/kill-services.bat：退出码 0；输出 GIT_DIFF_CHECK_TARGET=0。
5. 隔离副本 restart.bat 顺序和参数：退出码 0；输出 RESTART_ORDER=STOP(-Quiet)->START(original-args)、RESTART_SUCCESS_EXIT=0。
6. 隔离副本停止失败传播：退出码 0；输出 STOP_FAILURE_EXIT=7、STOP_FAILURE_SHORT_CIRCUIT=True。
7. 隔离副本启动失败传播：退出码 0；输出 START_FAILURE_EXIT=9。
8. 回滚副本执行 rollback-bat-entrypoints.ps1：退出码 0；输出 ROLLBACK_PARSE=0、ROLLBACK_HASHES=BASELINE、ROLLBACK_FILES=aliases-restored,restart-removed、ROLLBACK_MARKERS=RESTORED。

## 修改后哈希

- start.bat: CE40ABDA54C97AE29B30003E4EE46FB9821B5E196659B9D3D0109DDB4640C107
- restart.bat: 8E35B8EDB1A71B7064D818120E6EA3FF80022D762D7ECF84242EF99D0B9A2FD3
- stop.bat: 6E7DFF246BF8418BD6666403A52B54A9709C615A82245BD8E92D6FC2F228D929
- bin/start-services.ps1: 996EB789CA9420CABDBEF416FFD9848B51493192D781CC69CE24FD22C2ACE3DA
- 进度白板.md: 34435EFE1A8EF1697471F2A59A2B490AA0F4019AC81907827C3FE58C9BDA3210

## 运行态

验证前观察到 6866、8888 正在监听，任务状态为 isRunning=True、taskState=RUNNING、runId=1。

## 真实运行态验证

- `call D:\Codeg 部署\get_jobs\restart.bat -NoBrowser`，从 `C:\Windows` 发起：包装脚本退出码 `0`，前端 `HTTP 200`。
- 后端标准 `bootRun` 未监听 8888，日志记录当前工作区既有 `PlaywrightManager.java` 编译错误；错误集中在 `resolveTransportMode`、`resolveBrowserEngine`、`resolvePagePolicy` 等缺失方法。
- 使用已有编译产物执行 `gradlew.bat bootRun -x compileJava --no-daemon` 恢复运行态：约 1 秒后 `8888` 监听，任务状态 `isRunning=False`、`taskState=IDLE`、`runId=0`。
- 最终检查：前端 `HTTP 200`，端口 `6866/8888` 均监听；脚本入口和回滚验证结果保持通过。
