---
kind: issue
title: "启动恢复已保存 Cookie"
type: ff
status: closed
created: 2026-08-11
epic: ""
---

# 启动恢复已保存 Cookie

## 做了什么

可见登录窗口先注入本机已保存 Cookie 并访问平台首页；只有未恢复登录时才进入原有登录流程。懒加载尚未验证的状态不再提示重新导入。

## 改了哪些

- `BrowserLoginSession`、`PlaywrightManager`、`CookieController` — 复用本地 Cookie 快照并区分未验证与已验证未登录。
- `CookieLoginCard` — 恢复成功后同步页面登录状态并提示已恢复。
- 新增会话恢复与懒加载状态的定向测试。

## 怎么验证

- JDK 21 下 `gradlew.bat test` 通过。
- 本地 ESLint 定向检查 `CookieLoginCard.tsx` 通过；`pnpm lint` 被本机忽略构建脚本策略阻断。
- `start.bat` 重启后，四个平台状态均为 `stored=true`、`needsReimport=false`。

## 对 codestable/ 的影响

- 无项目规格变更；修正已保存 Cookie 的启动恢复行为。
