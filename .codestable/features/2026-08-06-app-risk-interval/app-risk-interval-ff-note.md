---
doc_type: feature-ff-note
feature: app-risk-interval
date: 2026-08-06
requirement: 调研 APP 风控并配置动作间隔与批次冷却
tags: [app, risk, interval, rate-guard]
---

## 做了什么

- 将搜索、翻页、详情、发送拆成独立的随机间隔，并统一到同一个账户级节奏时钟。
- 默认每 5 次成功发送后冷却 1200-1800 秒；任务剩余工作不足一个完整批次时跳过无意义冷却。
- 识别 HTTP 403/429 以及页面和响应中的频繁访问、安全验证、验证码等风险信号并停止任务。

## 参数来源

公开材料没有固定秒数；本次数值是保守工程起点，具体依据和限制见 `research-report.md`。

## 验证

Java 全量测试、页面定向 ESLint、Next.js 生产构建、patch 应用检查和隔离回滚均记录在 `validation-record.md`。
