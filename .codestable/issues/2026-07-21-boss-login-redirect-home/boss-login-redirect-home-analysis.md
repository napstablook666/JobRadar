---
doc_type: issue-analysis
issue: 2026-07-21-boss-login-redirect-home
status: confirmed
root_cause_type: missing-guard
related:
  - boss-login-redirect-home-report.md
tags: [boss, login, playwright, anti-detect, redirect]
---

# Boss 登录页强制回首页 根因分析

## 1. 问题定位

| 关键位置 | 说明 |
|---|---|
| `PlaywrightManager.java:123-139` | 运行时实际启动 Chromium + 共享 Context 的入口；`StartupRunner` 调 `playwrightManager.init()`。启动参数仅 CDP + maximized，**无** `AutomationControlled` 相关参数；UA 写死 macOS。 |
| `PlaywrightManager.java:180-190` | 仅注入 `anti-detection.js`（包一层 origin 判断）。这是 Boss 在**当前主路径**上的全部反检测。 |
| `src/main/resources/anti-detection.js` 全文 | 只做 `Function.prototype.toString` / console 伪装，**不处理** `navigator.webdriver`、plugins、languages、chrome runtime 等常见自动化指纹。 |
| `PlaywrightUtil.java:442-496` | 遗留工具链里有更完整的 `initStealth`（含 `webdriver→undefined`、cdc_* 清理、headers），**但未被 `PlaywrightManager` 调用**。 |
| `PlaywrightManager.java:208-284` | `setupBossPlatform`：注入 DB Cookie → 导航首页 → `setLoginStatus("boss", checkIfLoggedIn())` → 开监控。 |
| `PlaywrightManager.java:1567-1615` | `setLoginStatus(boss, false)` 会 `navigate` 到 `/web/user/?ka=header-login` 并尝试点二维码入口。代码里**没有**“从登录页 navigate 回首页”的逻辑。 |
| `PlaywrightManager.java:327-336` / `1433-1442` | 导航事件 + 每 3s 只做“未登录→已登录”检测；未登录时**不会**反向导航。 |
| `README.md` 已知问题 | 明确记录 Boss 检测机制会导致网页被回退（首页曾处理过，投递仍有回退类问题）。 |

结论前置：登录页“闪一下回首页”的 **导航离开动作不来自应用代码**；应用最多把人送进登录页。离开行为与站点风控/指纹判定一致，而主路径反检测相对 `PlaywrightUtil.initStealth` 明显残缺。

## 2. 失败路径还原

**正常路径（期望）**  
用户/系统进入 Boss → 未登录 → 打开 `/web/user/` 登录页 → 页面保持 → 扫码/验证码完成 → DOM 出现 `li.nav-figure` → `checkIfLoggedIn()==true` → `onLoginSuccess` 存 Cookie。

**失败路径（实际）**  
1. `StartupRunner` → `PlaywrightManager.init()` 拉起带 CDP 的 Chromium，Context UA=macOS，仅注入弱 `anti-detection.js`（`123-139`、`180-190`）。  
2. `setupBossPlatform` 注入可能过期的 Boss Cookie，打开 `zhipin.com`（`208-236`）。  
3. `checkIfLoggedIn()` 多为 false → `setLoginStatus("boss", false)` 可能自动 `navigate` 到登录页（`1575-1583`）；或用户在首页点「登录/注册」。  
4. 登录页短暂渲染后，**站点侧**（或站点脚本）判定自动化/会话异常，将页面回退到首页/求职列表（用户可见仍「登录/注册」）。  
5. 应用侧 `onFrameNavigated` / 定时检查只更新登录成功态，**不会**把登录页推回首页；因此回跳不在 `setLoginStatus` 成功分支。

**分叉点**  
- 行为分叉：进入 `/web/user/` 之后，站点是否允许停留。  
- 代码分叉：主运行路径 `PlaywrightManager` **未**接入已有较强 stealth（`PlaywrightUtil.initStealth`），指纹暴露面大于项目内已知能力。  
- 关键分叉位置：`PlaywrightManager.java:123-139` + `180-190` + `anti-detection.js`（缺 webdriver 等） vs `PlaywrightUtil.java:470-485`。

## 3. 根因

**根因类型**：`missing-guard`（主），附带 `config`（指纹/环境不一致）

**根因描述**：  
当前应用真正跑 Boss 的是 `PlaywrightManager` 共享浏览器路径。该路径对 Boss 的反检测几乎只有一个弱脚本，**没有**隐藏 `navigator.webdriver`、没有对齐 `PlaywrightUtil` 已有 stealth/headers，还用固定 macOS UA + 暴露 CDP 端口启动。Boss 侧对自动化访问会把敏感页（含登录页）回退——README 已记载同类“网页被回退”。因此用户点登录后能进登录页但无法停留，看起来像“强制跳回首页”。应用代码里找不到“登录页→首页”的主动 navigate，回跳是站点对自动化会话的响应；**可修的代码根因是主路径反检测缺失/不一致**。

**是否有多个根因**：**是（主次分明）**

| 级别 | 根因 | 证据 |
|---|---|---|
| 主 | `PlaywrightManager` 反检测弱于已知需求与项目内 `initStealth`，自动化指纹暴露 → 站点回退登录页 | Manager 仅 `anti-detection.js`；脚本无 webdriver；Util stealth 未接入；无 AutomationControlled 启动参数 |
| 次 | UA/平台声明与真实 Windows 环境不一致，加重指纹矛盾 | `NewContextOptions.setUserAgent(...Macintosh...)` 在 Windows 主机上 |
| 次 | 启动即注入可能过期的 Boss Cookie，可能造成半登录/脏会话，加剧站点异常跳转 | `setupBossPlatform` 无条件 `context.addCookies`；日志“共 17 条” |
| 次 | CDP `--remote-debugging-port=7866` 等自动化特征 | `launch` args 明文开启调试端口 |
| 排除 | 监控定时器“把登录页导航回首页” | `checkLoginStatus` 仅在变为已登录时 `setLoginStatus(true)`，无回首页 navigate |

> 说明：即使补齐 stealth，Boss 风控仍可能升级；本分析钉的是**当前代码可证明的主缺口**，不是保证 100% 永久绕过站点策略。

## 4. 影响面

- **影响范围**：不只“点登录闪回”；凡依赖在 Playwright 自动化浏览器内稳定打开 Boss 敏感页（登录、部分投递页）的流程都可能受同一指纹问题影响。README 亦提到投递过程刷新/回退。  
- **潜在受害模块**：`PlaywrightManager` 登录态、`Boss` 投递任务、Cookie 持久化、前端展示的 Boss 登录状态 SSE。  
- **数据完整性风险**：**低-中**。脏 Cookie 可能被反复读写；登录失败本身不直接写坏业务库，但“误判登录成功/失败”会影响状态与 Cookie 覆盖。  
- **严重程度复核**：**维持 P1**。核心平台登录阻塞，但可有外部浏览器登录后导 Cookie 等绕过；尚未证明全站所有用户路径完全不可用到 P0。

## 5. 修复方案

### 方案 A：补齐 `PlaywrightManager` 主路径反检测（推荐）

- **做什么**：  
  1. `launch` 增加常见降特征参数（如 `ignoreDefaultArgs` 去掉 `--enable-automation`，args 加 `--disable-blink-features=AutomationControlled` 等，按 Windows 实测微调）。  
  2. 将 `PlaywrightUtil.initStealth` 中的 **webdriver/cdc/plugins/languages/chrome** 脚本合并进 Context 级 `addInitScript`（或增强 `anti-detection.js`），确保 **每个** zhipin 文档加载都生效。  
  3. Context 补齐与 UA 一致的 `extraHTTPHeaders`（sec-ch-ua / platform / accept-language）。  
  4. UA 与真实 OS 对齐：Windows 主机用 Windows Chrome UA + `sec-ch-ua-platform: "Windows"`（或可配置）。  
  5. 可选：Boss 初始化前提供“清空过期 Cookie 再登录”开关/一次清理，避免脏会话。  
- **优点**：改动集中在 `PlaywrightManager` + `anti-detection.js`（或资源脚本），直接对准主运行路径；复用项目内已有 stealth 片段。  
- **缺点 / 风险**：反爬对抗无银弹；参数过激可能影响其它平台同 Context；需实机验证登录页可停留。  
- **影响面**：`PlaywrightManager.java`、`anti-detection.js`（或新脚本）；共享 Context 下其它平台指纹也会变（通常可接受）。

### 方案 B：Boss 独立 BrowserContext + 强化 stealth

- **做什么**：Boss 不再与猎聘/智联/51job 共享同一 Context；Boss 单独 Context/可选独立浏览器配置、完整 stealth、独立 Cookie。  
- **优点**：隔离 Cookie 污染与跨站指纹；后续可单独调 Boss 策略。  
- **缺点 / 风险**：改动面大（Page 生命周期、Cookie 存取、多页窗口模型、前端调试习惯）；回归成本高。  
- **影响面**：`PlaywrightManager` 架构级、`CookieService` 使用方式、可能影响“同一窗口多标签”产品形态。

### 方案 C：流程绕过（弱修复 / 运维向）

- **做什么**：登录阶段不依赖自动化页稳定性——引导用户用系统 Chrome 登录后导入 Cookie；或 init 时不自动 `navigate` 登录页，仅提示；加“清除 Boss Cookie 后重开”按钮。  
- **优点**：实现快，能立刻恢复“已登录 Cookie 投递”。  
- **缺点 / 风险**：不解决自动化浏览器内登录页回退；用户体验差；投递中若再触发回退仍可能炸。  
- **影响面**：Controller/前端文案 + Cookie 清理 API（已有 logout 可复用）。

### 推荐方案

**推荐方案 A**，理由：  
1. 根因证据直接落在主路径反检测缺失（file:line 可指）。  
2. 项目内已有更强 stealth 未接入，属于“能力已有、接线缺失”，改动相对 A/B 最小。  
3. 方案 B 过重；方案 C 不治本，可作 A 的配套（清脏 Cookie）而不是主方案。

**建议验收**：  
1. 清一次 Boss Cookie 后重启 → 打开登录页 **≥30s 不自动回首页**。  
2. 完成扫码登录 → `checkIfLoggedIn` 变 true，Cookie 落库。  
3. 回归猎聘/智联/51job 标签仍可打开。

## 分析引用

- 无 `.codestable/compound/` 历史沉淀可引。  
- README 已知 Boss 回退问题作为外部现象旁证，不单独当作代码根因。

## 6. 后续根因修正（2026-07-22）

实验证明 Plan A stealth **不足以**让登录页停留。Boss 识别 **活跃 Playwright/CDP 控制**。

正确修复：CDP 模式下 **detach Playwright** → HTTP /json/new hands-off 打开登录 → 轮询 → 重连。

详见 oss-login-redirect-home-fix-note.md round-3。