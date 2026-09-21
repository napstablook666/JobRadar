---
type: ff
status: closed
title: APP 外部申请与详情验证
---

# 做了什么

外部招聘页岗位进入待申请队列，详情页遇到访问验证时打开可见验证窗口；验证完成后复用当前运行时 Cookie，并从岗位游标继续处理。

# 改了哪些

- `Job51Entity`、`Job51Service` 和数据库兼容迁移增加申请路由、外部链接、外部申请状态与时间。
- 搜索解析、普通批量和 AI 批量路径识别外部申请链接，列表/KPI/状态筛选和手动完成接口同步支持待办。
- `Job51`、`Job51JobService` 和浏览器运行时增加验证请求、可见窗口、运行时 Cookie 同步、取消/超时和游标续跑。
- 51job 配置页与分析页展示外部链接、待办状态和手动完成操作。

# 怎么验证

- JDK 21 下 `./gradlew.bat test`：`BUILD SUCCESSFUL`。
- `front/node_modules/.bin/eslint.cmd .`：退出码 `0`。
- `front/node_modules/.bin/next.cmd build`：编译、TypeScript、14 个静态页面均通过。
- `git diff --check`：退出码 `0`。

# 对 codestable/ 的影响

新增本次快改记录；当前项目规格无变化。实现路径在 checkpoint 前已有历史未提交改动，按快照边界保留在工作区。
