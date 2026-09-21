---
kind: issue
title: "APP 详情页失败回退与岗位级投递"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# APP 详情页失败回退与岗位级投递

## 做了什么

修复详情页被访问验证拦截时的投递回退：优先使用搜索响应里的真实详情链接，详情路由失败后从搜索页按岗位直接投递，并继续以成功弹窗和数据库状态作为确认闸门；搜索响应中的 JD 快照先进入 AI 流程，详情页空壳或加载失败时保留 AI 判定输入。

## 改了哪些

- `src/main/java/com/getjobs/application/service/Job51Service.java`：旧快照遇到生成链接时刷新为搜索响应中的真实链接，并解析真实搜索字段 `jobDescribe`，沿用旧快照 JD 补齐逻辑。
- `src/main/java/com/getjobs/worker/job51/Job51.java`：增加站内/公开详情路由候选、整页批量入口查找、岗位级文本点击回退和访问验证诊断；详情导航前优先读取搜索快照 JD。
- `src/test/java/com/getjobs/application/service/Job51SearchJsonTest.java`：覆盖 `resultbody.job.items[].jobDescribe` 的真实响应形态。

## 怎么验证的

- Java 定向测试与全量 `220` 个测试：`BUILD SUCCESSFUL`，退出码 `0`；覆盖搜索 JD 字段和既有 APP 行为回归。
- `git diff --check`：退出码 `0`。
- 最新真实实例：搜索响应读取成功；真实详情链接触发访问验证后，回退实际点击岗位级“投递”入口，平台接口返回 `200`。
- 实例任务收敛为 `taskState=COMPLETED`，`aiProcessedCount=1`、`aiDeliveredCount=1`；统计从 `total=328/delivered=121` 变为 `total=329/delivered=122`，新增岗位状态为“已投递”。
- 重载后 `/api/health`、`/api/51job/health`、`/api/51job/status` 均返回 `200`；后台运行时 `hasBrowser=false`，51job 登录态保持有效。

## 对 `codestable/` 的影响

代码回退链路、搜索 JD 快照、真实成功投递、数据库状态和服务重载均已有记录；无需同步 project spec。
