# AI 超时一键批量重试验证记录

## 基线

- 工作区原有修改保持原样，基线副本与 SHA-256 记录位于 `baseline/` 和 `baseline-hashes.txt`。
- 本次变更范围包含后端服务、控制器、猎聘页面和两组测试文件。
- 未执行提交、推送或服务重载，避免影响当前运行态任务。

## 命令与结果

1. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; ./gradlew.bat test --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest --tests com.getjobs.application.controller.LiepinControllerPendingCleanupTest --no-daemon`
   - 输出：`BUILD SUCCESSFUL`，退出码 `0`。
2. `npm exec -- eslint app/liepin/page.tsx`
   - 输出：无 lint 错误，退出码 `0`。
3. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; ./gradlew.bat test --no-daemon`
   - 输出：`BUILD SUCCESSFUL`，退出码 `0`。
4. `npm run build`
   - 输出：Next.js 生产构建成功，`/liepin` 路由生成，退出码 `0`。
5. `git diff --check`
   - 输出：无空白错误，退出码 `0`。
6. `rollback-liepin-ai-batch-retry.ps1 -Preview -RepoRoot <isolated-root>`
   - 输出：预览模式逐文件报告基线哈希，退出码 `0`。
7. `rollback-liepin-ai-batch-retry.ps1 -RepoRoot <isolated-root>`
   - 输出：`rollback-check: restored listed files and hashes match`，退出码 `0`。

## 验收行为

- 空闲且存在 `aiRetryable` 时，批量接口返回 `success=true` 和新的 `runId`。
- 运行中重复触发由运行占用状态拦截。
- 当前轮待重试数量下降时进入下一轮，归零后以完成状态收尾。
- 当前轮没有进展时暂停并保留剩余数量，页面重新显示批量按钮。
- 停止请求在轮间等待和岗位处理阶段均可收敛到暂停状态。
