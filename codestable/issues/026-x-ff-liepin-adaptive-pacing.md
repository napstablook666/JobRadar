---
kind: issue
title: "猎聘自适应节奏与风控恢复"
type: ff
status: closed
created: 2026-08-12
epic: ""
---

# 猎聘自适应节奏与风控恢复

## 做了什么

将猎聘默认投递参数调整为正常节奏档，并把风控后的长休息、探测和恢复纳入账户级状态机，兼顾运行效率与可观察性。

## 改了哪些

- 默认每轮成功投递上限为 15；发送 20-35 秒，搜索 5-10 秒，翻页 3-5 秒，详情 5-8 秒。
- 每成功发送 15 次进入 180-300 秒批次冷却；数据库文件不在本批次修改。
- 风控信号后进入 30-45 分钟长休息，恢复后最多探测成功 3 次，再以 30-45 秒发送间隔恢复最多 8 次。
- 恢复阶段再次命中风控、页面失效、人工停止或发送结果不确定时，不自动重放或恢复，转为人工处理。
- 状态接口和猎聘页面展示当前档位、风控原因、信号次数、探测/恢复进度和长休息倒计时。
- 普通投递允许自动恢复；AI/暂缓岗位重试保持人工触发，避免共享账户状态被重试任务自动推进。

## 怎么验证的

- JDK 21 下定向 Java 测试：`LiepinConfigTest`、`LiepinRateGuardTest`、`LiepinJobServiceDeliveryTest`，`BUILD SUCCESSFUL`。
- JDK 21 下全量 `./gradlew.bat test --no-daemon`，`BUILD SUCCESSFUL`。
- 前端本地 `node_modules/.bin/eslint.cmd app/liepin/page.tsx`，退出码 `0`。
- 前端 `node_modules/.bin/next.cmd build`，编译成功并生成 14 个静态页面。
- `git diff --check`，退出码 `0`。
- `pnpm lint` 受本机 Corepack 依赖脚本审批策略拦截，已用项目现有 ESLint 二进制完成同等页面检查。
- checkpoint：`target/codex-git/checkpoints/20260812-123207-liepin-adaptive-pacing`。
- 实现提交：`c44c0e8`（`feat(liepin): 引入自适应投递节奏`）。

## 对 codestable/ 的影响

- 新增本任务记录；不改变 CodeStable 工具行为。
