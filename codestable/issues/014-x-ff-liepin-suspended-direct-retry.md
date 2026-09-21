---
kind: issue
title: "猎聘暂缓岗位原链接直达重试"
type: ff
status: closed
created: 2026-08-10
epic: ""
---

# 猎聘暂缓岗位原链接直达重试

## 做了什么

暂缓岗位重试改为读取启动时的完整岗位快照，逐条打开原岗位链接，不再依赖最新关键词搜索结果重新匹配。

## 改了哪些

- `LiepinService` — 增加暂缓岗位完整快照读取和原因计数。
- `LiepinJobService` / `Liepin` — 使用 BATCH_AUTO 直达流程，复用 AI 评分、话术和聊天回执；失配岗位保留并分类。
- 猎聘配置页与分析页 — 展示直达尝试、命中、保留和暂缓原因。

## 怎么验证的

暂缓快照、任务状态和控制器定向 Java 测试通过；Java 编译、前端 ESLint、TypeScript 检查和 `git diff --check` 通过。当前 8888 运行态未重载，未触发真实发送。

## 对 `codestable/` 的影响

无已记录真相受影响；新增实现约束已记录在本 ff。
