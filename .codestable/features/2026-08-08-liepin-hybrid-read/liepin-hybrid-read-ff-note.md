---
type: ff
status: open
---

# 猎聘混合读取

## 做了什么

猎聘岗位搜索在默认 hybrid 模式优先走 Java HttpClient，页面继续承担 DOM 加载和投递；HTTP 读取失败自动回到 Playwright 响应链路。51job 保留浏览器搜索，并统一回报读路径状态。

## 改了哪些

- 新增 LiepinHttpSearchClient、ReadRequestStatus。
- 接入 Liepin 搜索、分页和自定义薪资确认状态。
- 接入 51job 与两个平台服务状态快照。
- 增加端点、超时配置和本地 HttpServer 回归测试。

## 怎么验证

- 全量 Java 测试：169 个通过。
- git diff --check：无差异错误。
- 补丁隔离回放：通过，目标文件哈希一致。
- 回滚脚本检查：通过。

## 对 codestable/ 的影响

本轮结论只记录在本 feature 目录；浏览器后台模式的既有产物保持独立，未覆盖其他历史改动。
