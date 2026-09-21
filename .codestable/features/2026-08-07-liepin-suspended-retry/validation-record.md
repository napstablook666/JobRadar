# 筛选后暂缓岗位批量重试验证记录

## 基线与边界

- 工作区既有修改保持原样；本轮未重载 8888，未调用真实批量投递或清理接口。
- 隔离后端使用端口 8899 和数据库副本，验证结束后对应 Java 进程已停止，端口已释放。

## 命令与结果

1. `$env:JAVA_HOME='C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot'; ./gradlew.bat test --tests com.getjobs.application.service.LiepinServicePendingCleanupTest --tests com.getjobs.application.controller.LiepinControllerPendingCleanupTest --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest --no-daemon`
   - 输出：`BUILD SUCCESSFUL`，退出码 `0`。
2. `npx eslint app/liepin/page.tsx app/liepin/analysis/AnalysisContent.tsx`
   - 输出：`0` 个 error，保留 4 个既有 React Hook 或未使用变量 warning，退出码 `0`。
3. `npm run build`
   - 输出：Next.js 生产构建成功，`/liepin` 路由生成，退出码 `0`。
4. `$env:JAVA_HOME='C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot'; ./gradlew.bat test --no-daemon`
   - 输出：`BUILD SUCCESSFUL`，退出码 `0`。
5. `git diff --check`
   - 输出：无空白错误，退出码 `0`。
6. 隔离后端接口验证
   - 输出：`SUSPENDED_SUMMARY total=2 ai=1 network=1`；`ORDINARY_PENDING_OUTSIDE_QUEUE=2121`；`ISOLATED_PORT_8899_RELEASED=1`。

## 已验证行为

- 普通未投递记录不会进入一键批量重试目标集。
- AI 和网络暂缓记录分别计数，并作为批量任务的启动快照。
- 成功发送或明确筛选不通过时会清除对应暂缓记录，避免重复进入后续批量重试。
