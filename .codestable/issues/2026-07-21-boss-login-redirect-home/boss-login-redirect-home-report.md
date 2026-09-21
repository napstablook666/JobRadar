---
doc_type: issue-report
issue: 2026-07-21-boss-login-redirect-home
status: confirmed
issue_path: standard
severity: P1
summary: Boss 直聘登录页打开后被强制跳回首页，无法完成登录
tags: [boss, login, playwright, redirect]
---

# Boss 登录页强制回首页 Issue Report

## 1. 问题现象

在应用打开的 Boss 直聘页面中，点击「登录/注册」后，登录页（验证码登录/注册、APP 扫码登录）会短暂出现，随后被强制跳回 Boss 首页（或求职列表页），页面仍显示未登录态（仍可见「登录/注册」）。

## 2. 复现步骤

1. 本地启动后端（约 `http://127.0.0.1:8888`）与前端管理页（约 `http://127.0.0.1:6866`），使 Playwright 拉起 Boss 页面。
2. 在 Boss 页面点击「登录/注册」。
3. 观察到：进入登录页后，页面被强制跳回首页/求职列表，登录未完成。

复现频率：按用户反馈可稳定复现（至少在当前本地会话中可稳定观察到）。

## 3. 期望 vs 实际

**期望行为**：点击「登录/注册」后应停留在登录页，直到用户完成扫码/验证码登录，或明确取消。

**实际行为**：登录页短暂出现后被强制跳回首页，会话仍为未登录。

## 4. 环境信息

- 涉及模块 / 功能：Boss 直聘登录（Playwright 自动化浏览器内页面）
- 相关文件 / 函数（线索，非已确认根因）：
  - `src/main/java/com/getjobs/worker/manager/PlaywrightManager.java`（`setupBossPlatform`、`checkIfLoggedIn`、`setLoginStatus`、`onFrameNavigated` 等）
  - `src/main/resources/anti-detection.js`
  - Boss 投递逻辑相关：`Boss.java`（登录后投递链路，非本次回跳现象主路径）
- 运行环境：本地 dev（Spring Boot 后端 + Next 前端 + Playwright Chromium）
- 其他上下文：
  - 后端端口 8888；Playwright CDP 调试端口 7866
  - 启动日志可见：已注入 Boss Cookie（约 17 条）、Boss 反检测脚本注入、Boss 登录状态监控启用
  - README 已知相关现象描述：Boss 新增检测机制可导致网页被回退（首页访问已有说明，投递过程中仍有回退类问题）
  - 浏览器侧曾观察到 URL 类似：`https://www.zhipin.com/web/geek/job?query=前端工程师`

## 5. 严重程度

**P1** — Boss 是核心投递平台之一；无法完成登录则无法走通后续投递主链路。尚可用“手工外部浏览器登录后导入 Cookie”等绕过，故不定 P0。

## 备注

- 现象级约束：本报告只记录可观察行为，不写已确认根因。
- 快速通道判定（一次）：当前**不能**在 file:line 级别钉死唯一小范围根因，且可能涉及站点侧反爬/Cookie/指纹/监控导航等多因素，**不满足 fast-path**，建议 `standard` 路径进入 analyze。
- 用户原始描述：「点击 boss 直聘登录，进入登录页后会强制跳转回首页」。
