# APP 风控节奏适配验证记录

## 基线与修改范围

- 基线目录：`baseline/`
- 目标文件：8 个，当前 SHA256 见 `modified-artifacts.txt`，基线 SHA256 见 `baseline-hashes.txt`。
- 核心字段：搜索、翻页、详情、发送间隔；批次大小；批次冷却；风险信号识别。

## 命令、字面输出与退出码

1. 定向 Java 测试
   - 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinRateGuardTest`
   - 输出：`BUILD SUCCESSFUL`
   - 退出码：`0`
2. Java 全量测试
   - 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`
   - 输出：`BUILD SUCCESSFUL`
   - 退出码：`0`
3. 前端定向 ESLint
   - 命令：`npx eslint app/liepin/page.tsx`
   - 输出：空标准输出
   - 退出码：`0`
4. 前端生产构建
   - 命令：`npm run build`
   - 输出：`Compiled successfully`，路由包含 `/liepin`
   - 退出码：`0`
5. 差异空白检查
   - 命令：`git diff --check`
   - 输出：退出前置改动中的 CRLF 提示，无错误
   - 退出码：`0`
6. Patch 应用
   - 输入：`app-risk-interval.patch` 加 8 个基线文件
   - 输出：`PATCH_CHECK_OK`、`PATCH_APPLY_OK`、`PATCH_HASHES_OK`
   - 退出码：`0`
7. 回滚验证
   - 命令：`powershell -NoProfile -ExecutionPolicy Bypass -File rollback-app-risk-interval.ps1 -RepoRoot <隔离副本>`
   - 输出：`ROLLBACK_OK`、`ROLLBACK_FIXTURE_OK`、`ROLLBACK_SCRIPT_OK`、`ROLLBACK_HASHES_OK`
   - 退出码：`0`

## 备注

- 构建过程提示浏览器数据依赖可更新；本次未变更依赖版本。
- APP 官方材料未公开固定冷却秒数，本次数值属于本地保守策略，具体依据见 `research-report.md`。
