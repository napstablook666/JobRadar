---
kind: issue
title: "四平台统一 Cookie 登录"
type: ff
status: closed
created: 2026-08-08
epic: ""
---

# 四平台统一 Cookie 登录

## 做了什么

四个配置页统一使用 Cookie 导入登录，导入后立即展示登录检测结果并在下次启动自动恢复。

## 改了哪些

- `CookieLoginCard.tsx` — 新增共享 Cookie 导入表单与结果状态。
- 四个平台配置页 — 接入共享组件，移除旧的浏览器/扫码登录说明。

## 怎么验证的

定向 ESLint 和 Next.js 生产构建均通过；未重放任何真实 Cookie。

## 对 codestable/ 的影响

- 无已记录真相受影响；登录 API、Cookie 存储和旧扫码兼容接口保持不变。
