# 猎聘分页兼容验证记录

日期：2026-08-05

## 文件哈希

- 修改后源码：3CB1D8E86ECD6B5A374DF7ED35CB58714798CDEEEB28481F05305B85133E4251
- 回滚基线快照：720506592BCE6F73B9C116A08AA4FEEE10E2836EC58F8CA3394D4382CE25A974
- 修改后进度白板：F87015C706D96925D6543BD7E0B3DFAD15AD6085AA9F63322878C08A7E1857B4

## Java 回归

COMMAND: $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon
LITERAL OUTPUT: BUILD SUCCESSFUL in 15s
EXIT_CODE: 0

## 静态检查

COMMAND: git diff --check -- src/main/java/com/getjobs/worker/liepin/Liepin.java 进度白板.md
LITERAL OUTPUT: 空输出
EXIT_CODE: 0

## 真实单岗位复测

临时输入：关键词“放疗设备客户服务工程师”、薪资 0$100、AI 关闭、上限 1、间隔 0-0 秒。

COMMAND: PUT /api/liepin/config
LITERAL OUTPUT: enableAi=0, maxPerRun=1, minDelaySeconds=0, maxDelaySeconds=0
EXIT_CODE: 0

COMMAND: POST /api/liepin/start
LITERAL OUTPUT: {"success":true,"message":"猎聘任务启动成功","status":"started"}
EXIT_CODE: 0

COMMAND: POST /api/liepin/confirm
LITERAL OUTPUT: {"success":true,"message":"已记录确认动作"}
EXIT_CODE: 0

COMMAND: GET /api/liepin/status
LITERAL OUTPUT: {"messageType":"success","isRunning":false,"isLoggedIn":true,"message":"投递任务完成，共发起1个聊天"}
EXIT_CODE: 0

恢复配置：18 个关键词、AI=1、上限=30、间隔=2-5 秒。
恢复输出：PUT /api/liepin/config 返回 id=1、enableAi=1、maxPerRun=30、minDelaySeconds=2、maxDelaySeconds=5。
EXIT_CODE: 0

## 补丁与回滚验证

COMMAND: git apply --check --unsafe-paths --directory=target/local-audit/liepin-pagination-guard/patch-check liepin-pagination-guard.patch
LITERAL OUTPUT: 空输出
EXIT_CODE: 0

COMMAND: pwsh -NoProfile -ExecutionPolicy Bypass -File rollback-liepin-pagination-guard.ps1 -Root target/local-audit/liepin-pagination-guard/rollback-check
LITERAL OUTPUT: Rollback completed: pagination guard removed; unrelated Liepin changes preserved.
ROLLBACK_HASH: 720506592BCE6F73B9C116A08AA4FEEE10E2836EC58F8CA3394D4382CE25A974
EXIT_CODE: 0
