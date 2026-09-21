---
type: ff
status: closed
title: 51job 验证窗口阻塞修复
---

# 做了什么

修复 51job 访问验证在猎聘任务运行时卡在跨平台 Cookie 快照、未实际创建浏览器窗口的问题。

# 改了哪些

- `PlaywrightManager` 支持按平台读取 Cookie 快照，验证窗口实际创建后才通知前端。
- 51job 和猎聘调用各自的平台快照；验证窗口启动失败会显示实际原因。

# 怎么验证的

- JDK 21 下 `./gradlew.bat test`：`BUILD SUCCESSFUL`。
- `git diff --check`：退出码 `0`。
- 新增锁隔离回归：猎聘运行时锁被占用时，51job 快照不等待。

# 对 codestable/ 的影响

无影响。
