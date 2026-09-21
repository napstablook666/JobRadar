# 验证记录

## 基线与修改

- 工作区原有改动已保留，未执行 reset、checkout、提交或推送。
- 修改后快照位于 `modified/`，SHA-256 记录由快改产物生成。
- 当前 8888 服务有正在运行的旧版本投递任务，本轮未停止或覆盖该运行态。

## 命令与结果

1. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; ./gradlew.bat test --tests com.getjobs.worker.liepin.LiepinConfigTest --no-daemon`
   - 输出：`BUILD SUCCESSFUL`，退出码 0。
2. `npm exec -- eslint app/liepin/page.tsx`
   - 输出：无 lint 错误，退出码 0。
3. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; ./gradlew.bat test --no-daemon`
   - 输出：`BUILD SUCCESSFUL`，退出码 0。
4. `npm run build`
   - 输出：Next.js 生产构建成功，`/liepin` 路由生成，退出码 0。
5. `git diff --check`
   - 输出：无空白错误，退出码 0。
6. `GET http://localhost:8888/api/liepin/status`
   - 输出：当前任务仍为 `RUNNING`；旧运行态记录了 AI 超时，未因本次源码修改被打断。
7. `powershell -NoProfile -ExecutionPolicy Bypass -File rollback-liepin-ai-timeout-retry.ps1 -BaselineDir modified`，随后逐文件 SHA-256 校验
   - 输出：`rollback-check: restored listed files and hashes match`，退出码 0。

## 待运行验证

新后端加载后，构造一次 AI 超时，确认自动重试计数、`aiRetryable` 和 `POST /api/liepin/retry-ai` 的手动补跑行为。
