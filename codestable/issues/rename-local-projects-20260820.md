# 本地项目改名记录

日期：2026-08-20

## 范围

- `D:\TGcloud\CloudPaste` 已移动为 `D:\TGcloud\CloudShare`
- `D:\Codeg 部署\get_jobs` 已移动为 `D:\Codeg 部署\JobRadar`
- JobRadar 项目名称和启动类已同步为 `JobRadar` / `JobRadarApplication`
- 外部仓库 URL、数据库文件、Java `com.getjobs` 包名和既有部署资源名保留

## 验证

- JobRadar commit：`cac2e4b`
- CloudShare：`npm run build`、`npm test`（352 passed）
- JobRadar：`JAVA_HOME` 指向 JDK 21 后执行 `gradlew.bat test --no-daemon`，构建成功
- 两个仓库均通过 `git diff --check`
- 快照：`target/codex-git/checkpoints/20260820-092817-rename-resume-projects`
