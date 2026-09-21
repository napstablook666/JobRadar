---
kind: issue
title: "51job 间歇智能休息机制"
type: feature
status: open
created: 2026-08-10
epic: ""
---

# 51job 间歇智能休息机制

## 目标

51job 遇到访问验证、详情页受限、岗位标识读取异常或每日上限时，进入可恢复的间歇休息，不再把可恢复风险直接收敛为自动停止。

## 已完成

- `Job51` 返回 `REST_REQUIRED` 结果，保留关键词、关键词序号和页码恢复游标。
- `Job51JobService` 增加 `RESTING` 状态、三档随机休息、连续触发升级、跨日等待和可取消恢复调度。
- 休息期间释放 Playwright 访问锁，恢复时重新取得；运行中保存的配置从下一次休息开始生效。
- 配置表动态补充休息开关、三档区间和连续次数，前端回读/保存并展示倒计时、原因、次数和恢复位置。
- 休息态停止会取消定时任务并立即收敛为 `CANCELLED`。

## 验证

- Java 定向休息/状态测试通过。
- Java 全量测试通过，退出码 `0`。
- 51job 页面定向 ESLint、TypeScript 检查和 Next.js 生产构建通过。
- `git diff --check` 通过。
- 本轮 Git 基线快照：`target/codex-git/checkpoints/20260810-114244-app-smart-rest`。
- 本地记录提交：`2e3c6d0`。

## 后续

- 在真实运行态触发一次可恢复风险，确认状态接口保持 `RESTING`、倒计时递减并从游标继续。
