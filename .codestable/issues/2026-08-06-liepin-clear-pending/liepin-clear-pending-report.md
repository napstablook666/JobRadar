---
doc_type: issue-report
issue: 2026-08-06-liepin-clear-pending
status: confirmed
issue_path: fastforward
severity: P2
summary: 未投递岗位记录混入历史统计，影响已投递岗位查看
tags: [liepin, analytics, delivery, cleanup]
---

# 未投递岗位统计清理

## 问题现象

岗位数据表把所有扫描到但尚未确认发送成功的岗位都保留为未投递，分析页默认统计完整岗位池。

## 处理范围

- 删除 `delivered=0` 或 `delivered IS NULL` 的历史岗位记录。
- 保留全部 `delivered=1` 记录。
- 分页断点表保持原样。

## 清理前后

| 指标 | 清理前 | 清理后 |
|---|---:|---:|
| 岗位总数 | 3418 | 21 |
| 已投递 | 21 | 21 |
| 未投递 | 3397 | 0 |
| 分页断点 | 1 | 1 |
