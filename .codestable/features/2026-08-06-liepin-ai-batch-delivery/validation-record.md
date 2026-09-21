# 猎聘 AI 批量评分投递验证记录

## 基线与范围
- 工作区：`D:\Codeg 部署\get_jobs`
- 后端端口：`8888`；前端端口：`6866`
- 复用工作区已有未提交改动，不回滚其他功能。
- 运行态已有猎聘任务，验证期间没有中断它，也没有新建自动投递任务。

## 修改命令与输入
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat compileTestJava test
`pnpm exec eslint app/liepin/page.tsx app/ai-config/page.tsx`
`pnpm run build`
`git diff --check`
`GET /api/liepin/config`、`GET /api/liepin/status`、`GET /liepin`

## 字面输出与退出码
- Java：`BUILD SUCCESSFUL`，`100 tests completed`，退出码 `0`。
- 前端定向 lint：无输出，退出码 `0`。
- Next.js：`Compiled successfully`，13 个静态页面生成，退出码 `0`。
- `git diff --check`：退出码 `0`；只出现仓库已有 CRLF 转换提示。
- 运行态配置接口：HTTP `200`；旧进程回读 `enableAi=1`、`autoAiDelivery=1`、`aiMinScore=70`，该进程尚未加载本轮新增模式字段。
- 运行态状态接口：HTTP `200`；回读 `isRunning=true`、`isLoggedIn=true`，当前消息为节奏等待。
- 前端猎聘页面：HTTP `200`。
- 全量 `pnpm run lint`：退出码 `1`，36 个既有 error 分布在 Boss、智联、脚本和公共组件；本次涉及的两个页面定向 lint 通过。

## 变更行为
- 批量模式按岗位 ID 回填评分和话术，未知、重复、缺失或非法结果进入无效统计并跳过发送。
- REVIEW 只在评分达到复核线且没有硬性冲突时保留；PASS 需要达到通过线。
- 发送节奏闸门和聊天窗口成功确认逻辑保持原有行为。

## 回滚验证
- 脚本：`rollback-liepin-ai-batch-delivery.ps1`。
- 使用 `-Preview` 已验证能读取当前配置并生成 MANUAL/0 回滚输入；实际切换留待任务空闲后执行。
- 回滚预览字面输出：`PREVIEW_ONLY=1`，JSON 中 `autoAiDelivery=0`、`aiDeliveryMode=MANUAL`，退出码 `0`。
- 脚本 PowerShell 语法解析：`ROLLBACK_PARSE_ERRORS=0`。
- 产物自检：5 个记录文件均存在，补丁 `222481` 字节，`git diff --check` 退出码 `0`。
