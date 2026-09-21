---
kind: issue
title: "51job 稳健投递节奏参数"
type: ff
status: closed
created: 2026-08-12
epic: ""
---

# 做了什么

将 51job AI 投递切换到稳健节奏，降低连续请求密度，并保留已有的智能休息、取消和游标恢复机制。

# 改了哪些

- 默认单轮上限调整为 8 个，达到上限后休息 120-240 秒并自动继续。
- AI 岗位间隔调整为 20-40 秒，并强制最小间隔不低于 20 秒。
- 风控休息调整为 180-300、600-900、1800-2700 秒三档。
- 同步前端归一化、服务端校验和当前唯一 `job51_config` 配置行；未修改关键词、城市、薪资和登录配置。

# 怎么验证的

- 新增 `Job51ConfigPacingTest`，覆盖默认档位、20 秒下限和区间归一化。
- 数据库事务更新后回读确认目标字段正确，非目标配置保持不变。
- 定向 Java 测试：`Job51ConfigPacingTest`、`Job51BehaviorTest`、`Job51JobServiceStatusTest`，`BUILD SUCCESSFUL`。
- 全量 Java 测试：`./gradlew.bat test --no-daemon`，`BUILD SUCCESSFUL`。
- `front/app/51job/page.tsx` ESLint 和 Next.js 生产构建通过。
- `git diff --check` 通过。
- 本次 Git checkpoint：`target/codex-git/checkpoints/20260812-145216-51job-steady-pacing`。
- 实现提交：`ef909dd`。

# 对 codestable/ 的影响

新增本次快改记录；现有 `015-o-51job-smart-rest` 保持开放，继续承载真实运行态恢复验证待办。
