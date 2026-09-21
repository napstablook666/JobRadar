---
kind: issue
title: "修复 Boss 首页登录入口"
type: ff
status: closed
created: 2026-08-12
epic: ""
---

# 修复 Boss 首页登录入口

## 做了什么

修复 Boss 可见登录窗口访问固定登录 URL 后被平台重定向回首页的问题。现在先打开首页并点击可见的登录入口，找不到入口时才使用旧 URL 兜底。

## 改了哪些

- `BrowserLoginSession` — 增加 Boss 首页登录入口探测与点击流程。
- `BrowserLoginSessionTest` — 覆盖首页入口点击和无入口时的 URL 兜底。

## 怎么验证的

- JDK 21 下 `gradlew.bat test --tests com.getjobs.worker.manager.BrowserLoginSessionTest` 通过。
- JDK 21 下 `gradlew.bat test --tests com.getjobs.worker.manager.PlatformBrowserRuntimeLifecycleTest` 通过。
- JDK 21 下 `gradlew.bat test` 通过。
- `git diff --check` 通过。

### 运行态验收（2026-08-12）

- 提交 `5c32ece` 已加载到本地服务（JDK 21，API 端口 `8888`）。
- `POST /api/cookie/browser/open`（`platform=boss`）返回成功，浏览器状态为 `open=true`。
- 前端 Boss 页面可见“打开浏览器登录”和“获取并保存 Cookie”入口。
- 当前浏览器停在访问验证/未登录状态，`loggedIn=false`；未调用捕获接口，避免把未重新登录的旧会话再次保存。
- 完整的人工登录、Cookie 捕获和 `loggedIn=true` 验收需在可见浏览器中完成访问验证后继续。

## 对 `codestable/` 的影响

- 新增本次快改记录；Cookie 存储格式、登录状态 API 和数据库结构不变。
