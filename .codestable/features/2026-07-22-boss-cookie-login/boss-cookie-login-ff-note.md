---
doc_type: feature-ff-note
feature: boss-cookie-login
date: 2026-07-22
requirement:
tags: [boss, cookie, login]
---

## 做了什么
Boss 直聘登录改为 Cookie 导入主路径：禁用 hands-off 自动打开登录页；前端提供粘贴 Cookie + 导入；后端落库并注入浏览器后刷新检测登录态。

## 改了哪些
- `PlaywrightManager.java` — 去掉 init/`setLoginStatus(false)` 的 hands-off 触发；新增 `importCookies` / `parseCookiesFlexible`（JSON 或 header 格式）
- `CookieController.java` — 新增 `POST /api/cookie/import`
- `BossController.java` — logout 注释对齐 Cookie 登录
- `front/app/boss/page.tsx` — Cookie 登录卡片与导入逻辑

## 怎么验证的
- `JAVA_HOME=...jdk-21` 下 `./gradlew compileJava --rerun-tasks` → BUILD SUCCESSFUL
- 代码路径核对：未登录不再 startBossHandsOffLogin；import 写入 DB + addCookies + 导航检测

## 顺手发现（可选，不阻塞）
- `clearBossCookies` 等仍调用 `context.clearCookies()` 清掉共享上下文全部平台 Cookie — 不在本次范围
- hands-off 相关私有方法仍保留但已无自动入口，后续可删
