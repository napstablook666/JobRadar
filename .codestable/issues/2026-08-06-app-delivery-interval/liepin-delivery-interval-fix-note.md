---
doc_type: issue-fix-note
issue: liepin-delivery-interval
status: fixed
tags: [liepin, rate-guard, regression]
---

# 修复记录

## 修改

- `LiepinRateGuard.java`：等待前发布开始状态，等待后发布结束状态，已满足时限时发布无需额外等待状态。
- `LiepinRateGuardTest.java`：覆盖状态顺序、15 秒累计睡眠、无额外等待、停止中断和既有批次冷却。

## 行为验收

- 开始状态先于首个睡眠分片。
- 等待结束状态位于全部睡眠分片之后。
- 已满足间隔时假时钟不前进，也不再显示等待中提示。
- 停止信号会让当前动作返回失败状态。
