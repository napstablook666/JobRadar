---
doc_type: issue-fix
issue: 2026-07-21-boss-login-redirect-home
status: confirmed
path: standard
fix_date: 2026-07-22
related:
  - boss-login-redirect-home-analysis.md
tags: [boss, login, playwright, anti-detect, hands-off, cdp]
---

# Boss 登录页强制回首页 修复记录（round-3 hands-off）

## 1. 根因摘要（修正）

| 条件 | 登录页停留 |
|---|---|
| 系统 Chrome 146 + remote-debug，**无** Playwright/WebSocket 客户端 | **PASS** ≥20s |
| Playwright `connectOverCDP` + `page.goto(login)` | **FAIL** ~1.5–3s 回首页/`seoRefer=index` |
| Playwright 附着中 `/json/new` 开登录 | **FAIL** 标签约 2s 消失/回跳 |
| 清 Cookie + stealth（webdriver=null 等） | **仍 FAIL** |
| `browser.close()` 断开 Playwright（Chrome 仍活）后再 `/json/new` | **PASS** ≥12–20s |

**结论**：Boss 检测的是 **活跃 Playwright/CDP 控制会话**，不是单纯 Chromium 版本/Cookie/弱 stealth。Plan A stealth **不够**；产品修复必须是 **登录阶段 hands-off**（detach Playwright）。

顺带修复：Windows 上 Chrome launcher 秒退属正常；`waitForCdp` 以 `/json/version` 为准，避免误回退捆绑 Chromium 134。

## 2. 实际采用方案

**Hands-off 登录（CDP 模式）**：

1. 四平台初始化完成后，若 Boss 未登录且 `connectedOverCdp`：
   - `browser.close()` **仅断开** Playwright（不杀系统 Chrome）
   - 暂停全部平台监控；清空 Page/Context 引用
2. HTTP `PUT/GET http://127.0.0.1:7866/json/new?<loginUrl>` 打开登录页（**无** Playwright Page API / 无业务 WS）
3. 轮询 `/json/list`：登录页稳定停留；离开 `/web/user` 或超时后重连
4. `connectOverCDP` 重连，重绑四平台 Page，恢复监控；已登录则落库 Cookie
5. **删除** `setLoginStatus(false)` 内 Playwright `navigate(/web/user)` + 点二维码（该路径投毒登录页）

非 CDP（捆绑 Chromium）模式：拒绝再用 Playwright 导航登录页，打日志提示。

## 3. 改动文件清单

| 文件 | 改动 |
|---|---|
| `src/main/java/com/getjobs/worker/manager/PlaywrightManager.java` | CDP 优先；hands-off detach/open/poll/reconnect；去掉毒导航 |
| `src/main/java/com/getjobs/application/controller/BossController.java` | logout 先清 Cookie 再触发未登录/hands-off |
| `src/main/resources/anti-detection.js` | 前轮 Plan A 保留（辅助，非本轮主修复） |

## 4. 验证结果

| 项 | 结果 |
|---|---|
| `compileJava --rerun-tasks` | **通过** |
| 启动 `connectOverCDP` Chrome 146.0.7680.80 | **通过** |
| 日志：`启动 hands-off` → `detach` → `/json/new` 打开登录 | **通过** |
| 日志：登录页稳定停留 ≈16s+（无 Playwright 附着） | **通过** |
| 产品路径 list-only 轮询 20s 持续 `/web/user` | **PASS**（`target/local-run/boss-login-stay-hands-off.json`） |
| 对比：前轮 PW 附着 stay | **FAIL**（`boss-login-stay-round4.json` 等） |

**结论**：用户可见症状「登录页强制回首页」在 **产品 hands-off 路径** 上已消除（可停留完成扫码）。完整扫码→Cookie 落库仍依赖用户在 Chrome 窗口操作；自动化在离开登录页后会重连并检测。

## 5. 遗留 / 注意

1. hands-off 期间四平台 Playwright 自动化暂停（共享浏览器约束）。
2. `browser.close()` 偶发 `Cannot find command to respond: 17`，当前仍确认 CDP 可达且登录可停；若再现可改为更温和的 disconnect。
3. 非系统 Chrome CDP 模式无法 hands-off，登录引导被跳过（防毒导航）。
4. 二维码切换需用户手动（hands-off 禁止用 CDP WS 点控件，避免再被识别）。
5. `db/getjobs.db` 运行时脏数据勿当 fix 提交。

## 修复汇报

### 动了哪些文件
- `PlaywrightManager.java`（主）
- `BossController.java`（logout 顺序）
- issue 产物：fix-note / review 更新

### 是否触碰到分析范围外的文件?
仅 logout 顺序微调（避免 detach 竞态清 Cookie），属登录闭环必要。

### 是否引入了分析中没有提到的新概念/新结构?
引入 **hands-off login**（detach + HTTP `/json/new`），为 review 指出的 Plan A 不足后的正确主修复路径。

### 第一性原则 pre-pass 核对
- 外部行为目标：登录页可停留并完成登录
- 最小有效改动：去掉 PW 登录导航 + CDP 模式 hands-off
- 未做：独立浏览器进程拆分、删除 CDP 端口产品能力

### 复现步骤走一遍
1. 启动应用 → 系统 Chrome CDP 附着
2. Boss 未登录自动 hands-off 打开 `/web/user`
3. 登录页 ≥20s 不回首页 → **通过**
4. 用户扫码后应用重连并检测登录态
