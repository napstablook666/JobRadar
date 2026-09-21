# 51job AI 打招呼验证记录

## 基线
- 后端：`http://localhost:8888`。
- 前端：`http://localhost:6866`。
- 运行前配置：`enableAi=0`、`maxPerRun=10`、间隔 `5-10` 秒。
- 运行前状态：`isRunning=false`，`aiEnabled=false`。

## 修改与验证命令

### Java
- 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`
- 字面输出：`BUILD SUCCESSFUL in 24s`
- 退出码：`0`
- 覆盖：AI 响应空值、`false`、Markdown 包裹、上限收敛、最大间隔不小于最小间隔。

### 51job 页面定向 lint
- 命令：`pnpm exec eslint app/51job/page.tsx app/51job/analysis/AnalysisContent.tsx`
- 字面输出：`✖ 11 problems (0 errors, 11 warnings)`
- 退出码：`0`

### 生产构建
- 命令：`pnpm build`
- 字面输出：`Compiled successfully`、`Generating static pages (13/13)`
- 退出码：`0`

### 差异检查
- 命令：`git diff --check`
- 字面输出：无空白错误；仅有既有 CRLF 转换提示。
- 退出码：`0`

## 运行态接口结果
- 命令：`Invoke-RestMethod http://localhost:8888/api/51job/config` 与 `Invoke-RestMethod http://localhost:8888/api/51job/status`
- 关键输出：`enableAi=0`、`maxPerRun=10`、`minDelaySeconds=5`、`maxDelaySeconds=10`、`isRunning=false`、`aiEnabled=false`。
- 退出码：`0`

## 已知基线噪声
- 全仓 `pnpm lint` 仍会命中其他页面和脚本的既有规则错误；本轮改动文件定向 lint 无 error，生产构建与 TypeScript 检查通过。
