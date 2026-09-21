# 浏览器后台模式

## 做了什么

默认让浏览器自动化在后台运行，管理页面和扫码登录改为显式入口，减少页面跳转对前台操作的干扰。

## 改了哪些

- `PlaywrightManager` 增加 `background` / `visible-login` 两种模式。
- 后台模式使用 `--headless=new`，并统一处理网页 JavaScript 对话框。
- 未登录时后台只记录状态，扫码入口等待 visible-login 模式。
- 启动脚本默认关闭管理页并隐藏前端、后端子窗口。
- 状态接口返回 `browserMode` 与 `headless`。

## 怎么验证

- Java 全量测试通过。
- 启动脚本和回滚脚本 PowerShell AST 解析通过。
- 目标文件 `git diff --check` 通过。
- 运行态保留现有投递任务，真实重载验证待任务自然收尾。

## 对 `codestable/` 的影响

- 新增本目录的基线哈希、变更产物、验证记录和运行态回滚脚本。
- 项目进度白板已记录运行态重载与剩余验证项。
