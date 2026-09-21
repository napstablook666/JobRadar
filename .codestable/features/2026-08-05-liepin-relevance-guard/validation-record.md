# 本次快改验证记录

## 基线
- 工作区：`D:\Codeg 部署\get_jobs`
- 后端：`http://127.0.0.1:8888`
- 本次只改变后续 AI 投递判定和分析页默认筛选，历史岗位数据保持原样。

## 修改命令与输入
- 修改文件：`Liepin.java`、`LiepinAiGreetingDecisionTest.java`、`AnalysisContent.tsx`、`进度白板.md`。
- Java 回归：`$env:JAVA_HOME='C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.10-hotspot'; .\\gradlew.bat test --no-daemon`
- 前端构建：`pnpm run build`
- 统计接口：`GET /api/liepin/stats?statuses=已投递`
- 列表接口：`GET /api/liepin/list?statuses=已投递&page=1&size=1` 与 `GET /api/liepin/list?page=1&size=1`

## 字面输出与退出码
- Java：`BUILD SUCCESSFUL`，退出码 `0`。
- 前端：`Compiled successfully`、`Generating static pages (13/13)`，退出码 `0`。
- 已投递统计：`total=19, delivered=19, pending=0`。
- 已投递列表：`total=19, first.delivered=1`。
- 完整岗位池：`total=1706`。
- 前端 ESLint：退出码 `0`，`0 errors`，保留 3 个既有 Hook/未使用状态 warning。
- 回滚脚本 `rollback-liepin-relevance-guard.ps1 -DryRun`：`ROLLBACK_CHECK_OK`，退出码 `0`。

## 修改后 SHA-256
- `Liepin.java`: `FCD5E599985AE08948F2784AC76C289CD74618DA37A1FC7D6AE55287C0FB062C`
- `LiepinAiGreetingDecisionTest.java`: `029676853F7BC9BBEFE89875BD4FC542E75A3C5FAD1EE3349C05C208E9F394E0`
- `AnalysisContent.tsx`: `FCBE58AC84D0F3F3DD7DCBFBFCCF88F123B45C80C5220F9467607C8BB9325D2C`
- `进度白板.md`: `FBE63BBF942B696519FB8AF94A81FCD1CCB0F5981278BCC861FADA5C37BCE393`

## 观察到的行为
- AI `false`、空结果、请求异常、JD 缺失和相关度不通过均在点击聊天按钮前结束。
- 有效 AI 话术继续走聊天默认语确认、AI 话术发送和自动发简历链路。
