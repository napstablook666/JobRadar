---
type: ff
status: closed
title: 51job 系统 Chrome 验证窗口
---

# 做了什么

51job 访问验证窗口改为使用本机系统 Chrome 的独立 profile，避免验证页面进入 Playwright 捆绑 Chromium 的 `--no-sandbox` 启动链路；验证完成后仍复用运行时 Cookie 并从当前岗位游标恢复。

# 改了哪些

- 验证专用 `BrowserLoginSession` 支持指定系统 Chrome 可执行文件。
- 系统 Chrome 验证窗口显式排除 `--enable-automation` 和 `--no-sandbox` 默认参数，普通登录会话保留原有启动参数。
- 系统 Chrome 缺失时直接返回明确的验证窗口启动失败原因，不回退到原验证窗口。

# 怎么验证

- JDK 21 下 `./gradlew.bat test --tests com.getjobs.worker.manager.BrowserLoginSessionTest --tests com.getjobs.worker.manager.PlaywrightManagerModeTest`：`BUILD SUCCESSFUL`。
- JDK 21 下 `./gradlew.bat test`：`BUILD SUCCESSFUL`。
- `git diff --check`：退出码 `0`。

# 对 codestable/ 的影响

新增本任务记录；`015-o-51job-smart-rest` 仍保留真实运行态恢复验证待办。
