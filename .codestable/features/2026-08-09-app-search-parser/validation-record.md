# 猎聘搜索响应解析修复验证记录

日期：2026-08-09

## 基线与修改

- 基线：`.codestable/features/2026-08-09-app-search-parser/baseline/Liepin.java`
- 基线 SHA-256：`B610F0DAD2EBD2FF66C200EC62E788F426F315B7B7592F701101498BEF4FC44E`
- 白板基线 SHA-256：`43C9B80A536CF05E2871213810B9E42F780C79635022B62E9E3AC574DD2C308F`
- 修改文件：`src/main/java/com/getjobs/worker/liepin/Liepin.java`
- 新增测试：`src/test/java/com/getjobs/worker/liepin/LiepinSearchResponseTest.java`
- 白板更新：`进度白板.md`
- 补丁：`.codestable/features/2026-08-09-app-search-parser/liepin-search-parser.patch`
- 补丁 SHA-256：`584F26726CE045D1A596C38C3968BB27C4CACE39979090C457CF01C618C63B6C`

## Java 全量测试

命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`

字面输出：

```text
BUILD SUCCESSFUL in 20s
5 actionable tasks: 2 executed, 3 up-to-date
TESTS=190 FAILURES=0 ERRORS=0 SKIPPED=0
GRADLE_EXIT_CODE=0
```

退出码：`0`

测试报告汇总：`190` tests，`0` failures，`0` errors，`0` skipped。

## 解析定向测试

命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinSearchResponseTest`

字面输出：`BUILD SUCCESSFUL in 14s`、`TARGETED_EXIT_CODE=0`

退出码：`0`

## 隔离补丁回放

命令：`git apply --check --verbose --directory .codestable/features/2026-08-09-app-search-parser/patch-check-3 --unsafe-paths .codestable/features/2026-08-09-app-search-parser/liepin-search-parser.patch`

字面输出：`Checking patch ...Liepin.java...`、`Checking patch ...LiepinSearchResponseTest.java...`、`Checking patch ...进度白板.md...`

退出码：`0`

应用命令：`git apply --directory .codestable/features/2026-08-09-app-search-parser/patch-check-3 --unsafe-paths .codestable/features/2026-08-09-app-search-parser/liepin-search-parser.patch`

退出码：`0`

回放 SHA-256：`Liepin.java=AB82F07C1D657235093686BE9878CFB801A4530F3F1F35FAD12A8542C7ABC5A6`；`LiepinSearchResponseTest.java=14F292F2174A56C918B2964814B42AF588FE953D097FBBBB942C523856619001`；`进度白板.md=A4E20E49405343ECF28B4F600B776156886A54E7725A5DDB975E941C3ADEBA6D`，均与当前修改文件一致。

## 隔离回滚

命令：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .codestable/features/2026-08-09-app-search-parser/rollback-liepin-search-parser.ps1 -TargetRoot .codestable/features/2026-08-09-app-search-parser/rollback-check-2 -BaselineRoot .codestable/features/2026-08-09-app-search-parser/baseline`

字面输出：`status=rolled-back`、`sha256=B610F0DAD2EBD2FF66C200EC62E788F426F315B7B7592F701101498BEF4FC44E`、`board hash verified: 43C9B80A536CF05E2871213810B9E42F780C79635022B62E9E3AC574DD2C308F`、`test removed`、`rollback verified`

退出码：`0`

结果：隔离副本中的源码和进度白板均恢复到基线哈希，新增解析测试文件已移除。

## 差异检查

命令：`git diff --check`

字面输出：`DIFF_CHECK_EXIT_CODE=0`

退出码：`0`

## 真实运行态复测

时间：2026-08-09 14:05-14:13（本机 visible-login 隔离运行时）

- 服务重载：`bin/start-services.ps1 -BrowserMode visible-login -OpenBrowser`，前端 `6866` 与后端 `8888` 均就绪。
- 登录态：`/api/playwright/status` 返回 `deliveryRuntime=isolated`，APP 运行时 `isLoggedIn=true`；`/api/liepin/login-status` 返回 `isLoggedIn=true`。
- 启动命令：`POST /api/liepin/start`。
- 字面输出：`{"success":true,"runId":1,"message":"猎聘任务启动成功","status":"started"}`。
- 运行配置回读：`MANUAL`、AI 开启、`maxPerRun=10`、薪资范围 `6$10`。
- 任务终态：`PAUSED`、`isRunning=false`、`runId=1`、`scanned=9`、`salaryEligible=9`、`delivered=5`、`pendingGreeting=null`。
- AI 统计：`candidateCount=6`、`messageCalls=6`、`passed=5`、`aiTimeouts=0`、`buttonFailures=0`、`confirmationFailures=0`。
- 发送回执：4 条岗位确认出现 AI 话术和简历；第 5 条在停止请求收尾时确认 AI 话术，简历动作被停止信号打断，未计作简历成功。
- 任务收尾：普通投递和暂缓重试槽均已停止；后台模式重载后健康接口返回 `200`，两个任务槽均为 `IDLE`。
- 登录残留：后台模式重载后 `/api/cookie/status?platform=liepin` 返回 `stored=true`、`loggedIn=false`、`needsReimport=true`；后续新任务需在 visible-login 完成登录捕获后再切回后台。

本轮真实复测结论：搜索响应、薪资确认、翻页、AI 话术、聊天确认和简历发送链路均有实际成功证据；未观察到按钮失效、确认失败或 AI 超时。

## 收口复跑

命令：`$env:JAVA_HOME='C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot'; .\\gradlew.bat test --no-daemon`

字面输出：`BUILD SUCCESSFUL in 29s`；测试报告统计 `TESTS=198 FAILURES=0 ERRORS=0 SKIPPED=0`。

退出码：`0`
