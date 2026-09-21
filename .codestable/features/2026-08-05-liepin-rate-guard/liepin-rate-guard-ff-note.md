---
doc_type: feature-ff-note
feature: liepin-rate-guard
date: 2026-08-05
requirement: 统一岗位访问节奏，降低短时间连续搜索、翻页、详情读取和发送造成的高频风险
tags: [liepin, rate-guard, cooldown, delivery]
---

## 做了什么

- 新增账户级节奏闸门，统一约束搜索、翻页、详情和发送动作。
- 默认单次最多 10 个岗位；发送间隔收敛到 90-180 秒。
- 连续 5 次成功发送后冷却 1200-1800 秒。
- 搜索响应出现 403/429 时停止当前任务并记录进度信息。

## 改了哪些

- `LiepinRateGuard.java`：动作间隔、批次冷却、停止信号和可测试时钟。
- `Liepin.java`：接入四类动作，移除旧的岗位间隔等待，增加响应状态闸门。
- `LiepinConfig.java`、`LiepinService.java`、`front/app/liepin/page.tsx`：默认值和历史低间隔配置收敛。
- `LiepinRateGuardTest.java`：覆盖共享时钟、批次冷却、停止信号和旧配置收敛。

## 怎么验证的

- Java 全量测试通过，`BUILD SUCCESSFUL`。
- 猎聘页面 ESLint 通过，Next 生产构建通过。
- `git diff --check` 通过。
- `rate-guard.patch` 在重建的前置工作树中应用检查通过。
- `rollback-rate-guard.ps1` 提供反向补丁回滚。

## 对 `.codestable/` 的影响

- 已新增本次快改记录和验证记录；未修改项目规格。
