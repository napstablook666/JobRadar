# 猎聘批量发送重定位修复验证记录

## 范围

- 工作区：`D:\Codeg 部署\get_jobs`
- 复用已有未提交改动，不回滚其他模块。
- 当前运行中的投递任务未停止、未重载；验证只编译和检查源码。

## 验证命令与字面结果

1. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests com.getjobs.worker.liepin.LiepinBatchDeliveryGuardTest --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest`
   - `BUILD SUCCESSFUL`，退出码 `0`。
2. `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test`
   - `BUILD SUCCESSFUL`，测试 XML 汇总 `125` 个测试，失败/错误 `0`，退出码 `0`。
3. `pnpm exec eslint app/liepin/page.tsx app/ai-config/page.tsx`
   - 无输出，退出码 `0`。
4. `pnpm run build`
   - `Compiled successfully`，生成 `13` 个静态页面，退出码 `0`。
5. `git diff --check`
   - 无差异错误，退出码 `0`。
6. `rollback-liepin-batch-send-retry.ps1 -Preview`
   - `PREVIEW_ONLY=1`，6 个当前哈希均与回滚保护哈希一致，退出码 `0`。
7. 隔离副本实际回滚
   - `ISOLATED_ROLLBACK_EXIT=0`。
   - `ISOLATED_ROLLBACK_HASHES_MATCH=1`，新回归测试文件已移除。

## 运行态边界

- 日志中的旧运行态仍可能继续使用旧 class，需服务空闲后重载源码才会加载本修复。
- 已在服务空闲后重载并完成真实自动投递复测；真实结果见 `real-run-record.md`。
