---
kind: issue
title: "四平台 Cookie 浏览器登录与生命周期"
type: feature
status: open
---

# 四平台 Cookie 浏览器登录与生命周期

## 目标

四个平台都支持可见浏览器登录后点击获取并保存 Cookie，以及手动 Cookie 导入；停止投递时关闭应用创建的浏览器实例。

## 已完成

- 新增独立的四平台可见登录会话，登录状态只做轮询展示，用户点击获取后复用现有 Cookie 导入链路落库并注入投递运行时。
- 共享 Cookie 登录卡片增加浏览器登录/手动填写两种模式，以及“获取并保存 Cookie”按钮和状态轮询。
- 移除配置保存、SSE 登录事件和后台登录检测中的自动 Cookie 持久化。
- 修复猎聘登录误判：收窄登录入口选择器，增加 `lt_auth` 与 `UniqueKey/user_name` 组合兜底，避免已登录页面被无关 `href*='login'` 误判。
- 捕获回调带回导入后的登录检测结果，避免页面跳转瞬间的假阴性覆盖有效 Cookie。
- 四个平台登录检测改为“明确登录入口/安全验证优先判未登录，明确用户入口才判已登录，未知保持未登录”，收敛公共页和验证页误报。
- Cookie 状态接口的 `loggedIn` 回写配置页，刷新页面时不再被旧 SSE 状态覆盖。
- 四个平台停止接口等待任务收尾后关闭对应浏览器；`stop.bat` 扩展为停止四个平台并清理项目 profile。
- 状态接口只返回登录与保存状态，不返回 Cookie 内容。
- 后台持久运行时固定使用普通 Windows Chrome UA，保留无界面模式和现有自动化特征设置。
- 猎聘初次导航与 Cookie 导入后的登录检测，在存在认证 Cookie 时等待 SPA 首页最多 3 次；安全验证页仍判为未登录。

## 验证

- Java 全量测试通过。
- 前端四页定向 ESLint 与 Next.js 生产构建通过。
- 浏览器状态与捕获接口实测覆盖四个平台；未打开浏览器时捕获接口返回明确提示，响应未包含 Cookie 内容。
- Java 全量测试、前端 lint 和 Next.js 生产构建通过。
- 猎聘实例重放：页面点击“打开浏览器登录”后点击“获取并保存 Cookie”，返回 `captured=true`、`loggedIn=true`、`count=30`，状态接口为 `needsReimport=false`，前端显示“当前已登录”。
- `stop.bat` 实测退出码为 0，服务端口和项目 Chrome 实例均为 0。
- 本轮代码级验证：Java `test`、前端 `pnpm lint`、前端 `pnpm build` 均通过；按要求未追加实例复测。
- 51job 状态锁回归：投递期间 `/api/51job/status` 不再等待 Playwright 临界区；服务重载后约 0.2 秒返回，`isLoggedIn=true`，Cookie 状态为 `stored=true`、`needsReimport=false`。
- 本轮回归：`PlatformBrowserRuntimeLifecycleTest` 与 `PlaywrightManagerModeTest` 定向测试通过，Java 全量测试 `BUILD SUCCESSFUL`。
- 服务重载回读：`/api/health` 返回 `UP`；`/api/cookie/status?platform=liepin` 返回 `stored=true`、`loggedIn=true`、`needsReimport=false`。

## 后续

- 使用四个平台各自重新登录，做一次真实 Cookie 恢复复测。
