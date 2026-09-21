---
doc_type: feature-ff-note
feature: liepin-delivery-stall
date: 2026-08-05
requirement:
tags: [liepin, delivery, playwright, status]
---

## 做了什么
修复猎聘开始投递后页面无响应的问题，并让页面持续显示任务的真实状态和最近消息。

## 改了哪些
- `src/main/java/com/getjobs/worker/liepin/Liepin.java` — 移除异步响应正文读取，使用同步 `waitForResponse` 捕获搜索接口；搜索导航最多重试一次，聊天点击不自动重试。
- `src/main/java/com/getjobs/worker/manager/PlaywrightManager.java` — 共享 Playwright 访问统一串行化，覆盖初始化、登录监控、Cookie 操作和平台投递。
- `src/main/java/com/getjobs/worker/service/LiepinJobService.java` — 保存最近消息、消息类型和时间，并从任务初始化阶段维护运行状态。
- `front/app/liepin/page.tsx` — 增加状态轮询、启动/停止反馈和错误状态展示。

## 后续增量：逐岗位 AI 招呼语
- `Liepin.java` — 从搜索快照或岗位详情页读取 JD；按岗位调用现有 AI 服务；没有 JD 或 AI 失败时回退猎聘预设语。
- `LiepinJobService.java` / `LiepinController.java` — 增加待确认岗位状态和 `/api/liepin/confirm`，每个岗位最多等待 10 分钟，停止任务会取消确认。
- `LiepinMessageRequest.java` — 仅在发送请求中唯一识别到消息字段时改写 AI 话术，无法唯一识别则保留原请求。
- `LiepinService.java` — 保存 `job_description`，2xx 响应后才标记已投递；批量保存同时写入 JD。
- `front/app/liepin/page.tsx` — 增加 AI 开关、岗位上限、随机间隔和逐岗位确认卡。
- 既有“继续聊”只视为已有会话并跳过，不更新本次投递状态。

## 怎么验证的
- `JAVA_HOME=...jdk-21 .\gradlew.bat compileJava --no-daemon` → BUILD SUCCESSFUL
- `JAVA_HOME=...jdk-21 .\gradlew.bat test --no-daemon` → BUILD SUCCESSFUL
- `pnpm exec eslint app/liepin/page.tsx` → 0 errors（保留原有 warnings）
- `pnpm build` → 构建成功，生成 `/liepin` 页面
- 本地服务验证：`GET /api/liepin/status` 返回 `success=true`、`isRunning=false`、`isLoggedIn=true`、`message=尚未启动投递任务`；`/liepin` 返回 HTTP 200
- `pnpm exec eslint app/liepin/page.tsx` → 0 errors
- `JAVA_HOME=...jdk-21 .\gradlew.bat test --no-daemon` → BUILD SUCCESSFUL（含 `LiepinMessageRequestTest`）
- 未执行真实猎聘投递，避免重复触发聊天。

## 对 `.codestable/` 的影响
- 仅新增本次快改记录，不改变现有规格或历史事项。
