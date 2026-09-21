# 猎聘双任务槽验证记录

日期：2026-08-08
工作区：`D:\Codeg 部署\get_jobs`

## 基线与构建

```text
命令：$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; ./gradlew test --no-daemon --console=plain --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest --tests com.getjobs.worker.service.LiepinJobServiceShutdownTest --tests com.getjobs.application.controller.LiepinControllerPendingCleanupTest
退出码：0
字面结果：BUILD SUCCESSFUL in 25s

命令：pnpm lint
退出码：0
字面结果：0 errors and 0 warnings

命令：pnpm build
退出码：0
字面结果：Compiled successfully；Generating static pages (14/14)

命令：git diff --check
退出码：0
字面结果：DIFF_CHECK=PASS
```

## 回滚验证

```text
命令：.\.codestable\features\2026-08-08-liepin-parallel-tasks\rollback-liepin-parallel-tasks.ps1 -Preview
退出码：0
字面结果：10 个目标文件的 CURRENT_HASH 与 EXPECTED_HASH 全部一致

命令：隔离 rollback-check 执行 rollback-liepin-parallel-tasks.ps1
退出码：0
字面结果：ROLLBACK_APPLIED=1；10 个目标文件 BASELINE_MATCH；ROLLBACK_ISOLATED=PASS
```

## Patch 回放

```text
命令：git apply --check --unsafe-paths --whitespace=nowarn --directory=.codestable\features\2026-08-08-liepin-parallel-tasks\patch-check\workspace -p1 -- liepin-parallel-tasks.patch
退出码：0
字面结果：PATCH_CHECK=PASS

命令：在同一隔离目录执行 git apply，并对 10 个目标文件做统一换行后的 SHA-256 比较
退出码：0
字面结果：PATCH_APPLY=PASS；PATCH_NORMALIZED_HASHES=PASS
```

## 运行态观察

```text
命令：Invoke-RestMethod http://localhost:8888/api/liepin/status
退出码：0
字面结果：success=true；当前普通投递 taskState=RUNNING；当前 8888 实例尚未加载本次源码，因此双槽字段等待下一次重载后出现
```

本次验证未停止或重载正在执行的任务；新后端加载后的双任务真实并行小批复测单独记录。
