---
kind: issue
title: "Cookie 持久化失效提示"
type: ff
status: closed
created: 2026-08-08
epic: ""
---

# Cookie 持久化失效提示

## 做了什么

保存本机 Cookie 后，登录检测仍失败时明确提醒重新获取并导入，不再只给模糊提示。

## 改了哪些

- `CookieController` — 新增不返回 Cookie 内容的状态接口，返回已保存、登录检测和需重导入状态。
- `CookieLoginCard` — 页面加载和导入后读取状态，展示重新获取 Cookie 的提示。

## 怎么验证的

后端 `compileJava`、前端定向 ESLint 和 Next.js 生产构建通过。

## 对 codestable/ 的影响

- 无已记录真相受影响；本地 Cookie 存储格式与导入 API 保持兼容。
