# 猎聘 AI 筛选自动投递验证记录

## 基线
- 工作区：`D:\Codeg 部署\get_jobs`
- 后端：`http://localhost:8888`
- 前端：`http://localhost:6866`
- 自动投递开关在验证期间保持关闭。

## 修改命令与输入
- Java：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; ./gradlew.bat test`
- 猎聘定向 Java：同一 JDK 命令加 `--tests com.getjobs.worker.liepin.LiepinAiAssessmentTest --tests com.getjobs.worker.liepin.LiepinConfigTest --tests com.getjobs.worker.liepin.LiepinAiGreetingDecisionTest`
- 前端 lint：`pnpm exec eslint app/liepin/page.tsx`
- 前端构建：`pnpm run build`
- 空白检查：`git diff --check`
- 运行态回读：`GET /api/liepin/config`、`GET /api/liepin/status`

## 字面输出与退出码
- 定向 Java：`BUILD SUCCESSFUL`，退出码 `0`。
- Java 全量：`BUILD SUCCESSFUL`，退出码 `0`。
- 猎聘页面 ESLint：无输出，退出码 `0`。
- Next.js 构建：`Compiled successfully`、13 个静态页面生成，退出码 `0`。
- `git diff --check`：退出码 `0`；仅报告仓库既有 CRLF 转换提示。
- 配置回读：`{"id":1,"enableAi":1,"autoAiDelivery":0,"aiMinScore":70,"maxPerRun":30,"minDelaySeconds":2,"maxDelaySeconds":5}`。
- 状态回读：`{"success":true,"isRunning":false,"message":"尚未启动投递任务","pendingGreeting":null}`。
- 基线隔离目录补丁检查：`PATCH_CHECK_EXIT=0`、`PATCH_APPLY_EXIT=0`、`NEW_FILES=3`。
- 回滚隔离目录：`ROLLBACK_EXIT=0`、`RESTORED_FILES_MATCH=7`、`NEW_FILES_REMOVED=3`。

## 运行范围
- 只重启了仓库后端以加载新类和数据库兼容迁移，保留前端与浏览器会话。
- 没有启动自动投递任务，没有调用真实 AI 评分或发送岗位。
