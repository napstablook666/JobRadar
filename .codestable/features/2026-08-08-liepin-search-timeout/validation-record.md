# 搜索超时修复验证记录

日期：2026-08-08

## 基线与修改

- 基线目录：`.codestable/features/2026-08-08-liepin-search-timeout/baseline/`
- 修改文件：`Liepin.java`、`LiepinHttpSearchClient.java`、`LiepinHttpSearchClientTest.java`
- 关键字段：搜索响应等待 12 秒、导航等待 15 秒、HTTP 搜索薪资字段、翻页 HTTP 回退、整数年薪输入边界。

## 编译与测试

命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`

字面输出：`BUILD SUCCESSFUL in 23s`

退出码：`0`

定向测试：`com.getjobs.worker.liepin.LiepinHttpSearchClientTest`

字面输出：`BUILD SUCCESSFUL in 23s`

退出码：`0`

## 隔离补丁回放

命令：`git apply --check --verbose --directory .codestable/features/2026-08-08-liepin-search-timeout/patch-check --unsafe-paths .codestable/features/2026-08-08-liepin-search-timeout/liepin-search-timeout.patch`

字面输出：`Checking patch ...Liepin.java`、`Checking patch ...LiepinHttpSearchClient.java`、`Checking patch ...LiepinHttpSearchClientTest.java`

退出码：`0`

应用命令：`git apply --directory .codestable/features/2026-08-08-liepin-search-timeout/patch-check --unsafe-paths .codestable/features/2026-08-08-liepin-search-timeout/liepin-search-timeout.patch`

退出码：`0`

结果：三份回放文件 SHA-256 与当前修改文件一致。

## 隔离回滚

命令：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File rollback-liepin-search-timeout.ps1 -TargetRoot rollback-check -BaselineRoot baseline`

字面输出：`rollback verified: targetRoot=...rollback-check`

退出码：`0`

结果：三份文件恢复到基线哈希。

## 运行态

- 后端健康接口：`success=true`、`status=healthy`
- 登录状态：`isLoggedIn=true`
- 临时运行配置：`maxPerRun=1`、`salaryCode=6$10`、`aiDeliveryMode=BATCH_AUTO`、`aiMinScore=70`
- 启动接口：`success=true`、`runId=1`
- 完成状态：`taskState=COMPLETED`、`isRunning=false`、`lastDeliveredCount=1`
- 运行汇总：`scanned=82`、`salaryEligible=69`、`salarySkipped=13`、`aiSummary.passed=1`、`messageCalls=1`
- 聊天确认日志：`默认沟通语已确认: aiApplied=true, resumeApplied=true`
- 目标岗位数据库状态：`delivered=1`
- 搜索读取状态：`route=browser`、`fallback=false`，翻页响应已捕获。

配置恢复命令：读取 `runtime-config-original.json` 后 PUT 回配置接口。

恢复字面输出：`restoredMaxPerRun=10, originalMaxPerRun=10, equal=true`

恢复验证退出码：`0`
