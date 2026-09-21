# 猎聘 AI 超时自动重试

## 做了什么

AI 评分、批量话术和逐岗话术请求现在只对超时自动重试；默认追加 3 次，等待按次数递增。超时耗尽的岗位保留当前页，不保存虚假的完成页码。

页面增加自动重试开关、追加次数、间隔和“重试 AI 超时”按钮。手动入口复用当前页断点，已发送岗位仍由“继续聊”跳过。

## 改了哪些

- `LiepinConfig`、`LiepinConfigEntity`、`ConfigService`、`LiepinService`：配置字段与旧库自动迁移。
- `Liepin`：超时重试、重试统计、待重试计数和页码闸门。
- `LiepinJobService`、`LiepinController`：状态字段与 `POST /api/liepin/retry-ai`。
- `front/app/liepin/page.tsx`：配置、状态和手动操作。
- 猎聘配置与批量投递回归测试。

## 怎么验证

见同目录 `validation-record.md`。Java 全量测试、页面 ESLint、Next.js 生产构建和 `git diff --check` 均通过。

## 对 codestable/ 的影响

本次是独立快改，未修改 Vision 或稳定规格；当前活动投递任务未被重载打断，运行态加载和真实超时复测待任务自然结束后进行。
