---
doc_type: feature-ff-note
feature: app-aggressive-interval
date: 2026-08-07
requirement: 提高 APP 岗位处理效率，收紧页面动作间歇
tags: [app, interval, rate-guard, performance]
---

## 做了什么

- 将搜索、翻页、详情、发送和批次冷却调整为更短的随机区间。
- 发送下限调整为 90 秒，单次岗位上限和风险信号停止逻辑保持原值。
- 当前数据库配置已同步到新参数，下一轮任务读取新值。

## 改了哪些

- `LiepinConfig.java`、`LiepinService.java`、`Liepin.java`：更新默认值、边界和校验提示。
- `front/app/liepin/page.tsx`：更新加载归一化、输入下限和页面说明。
- `LiepinRateGuardTest.java`：更新旧值收敛断言。
- 新增补丁、数据库 SQL、基线哈希、修改哈希和回滚脚本。

## 怎么验证的

- Java 定向与全量测试、页面 ESLint、Next.js 生产构建通过。
- 数据库事务读取目标值通过。
- 补丁隔离应用和源码/数据库隔离回滚通过。

## 对 `.codestable/` 的影响

- 新增本次快改记录与验证记录；未修改项目规格。
