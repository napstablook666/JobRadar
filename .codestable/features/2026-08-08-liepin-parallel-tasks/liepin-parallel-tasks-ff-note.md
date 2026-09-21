---
doc_type: feature-ff-note
feature: liepin-parallel-tasks
date: 2026-08-08
requirement: 猎聘普通投递与筛选后暂缓岗位批量重试可以并行运行
tags: [liepin, parallel, retry, task-state, ui]
---

## 做了什么

- 普通投递和筛选后暂缓岗位批量重试拆成两个独立任务槽，可同时启动、停止和收尾。
- 两个任务槽各自保留运行状态、轮次、剩余数、成功数、日志、待确认岗位和确认路由。
- 共享账户节奏与浏览器临界区，保留资源访问顺序和登录监控租约。
- 配置页和分析页分别展示两个任务槽；清理未投递岗位会检查任一任务槽。

## 改了哪些

- `LiepinJobService`、`LiepinController`：双槽状态契约、独立启动/停止/确认和旧顶层字段兼容。
- `front/app/liepin/page.tsx`：暂缓任务面板、独立停止按钮、轮次/剩余数、日志和确认卡。
- `front/app/liepin/analysis/AnalysisContent.tsx`：双槽状态轮询、暂缓独立停止、清理双槽互斥。
- 补充双槽状态、并行启动、独立停止、确认路由和清理检查测试。

## 怎么验证

- `pnpm lint`：通过，0 error / 0 warning。
- `pnpm build`：通过，14 个 Next.js 路由生成。
- Java 猎聘定向测试：通过，构建成功。
- `git diff --check`：通过。
- 8888 当前实例仍在执行普通投递，源码新版本留待自然收尾后重载；未打断现有任务。

## 对 `.codestable/` 的影响

- 新增本次 `ff` 记录、基线快照、patch、验证记录和回滚脚本。
- 未提交、未推送、未重载运行服务；真实双任务并行复测留到新版本加载后。
