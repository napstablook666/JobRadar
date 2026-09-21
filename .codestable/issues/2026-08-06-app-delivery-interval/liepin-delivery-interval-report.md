---
doc_type: issue-report
issue: liepin-delivery-interval
status: fixed
severity: medium
root_cause_type: timing-observability
tags: [liepin, delivery, rate-guard, progress]
---

# 投递间歇提示时序异常

## 现象

任务页面会显示“节奏控制：发送等待 N 秒后继续”，但提示出现后马上进入下一步，用户难以判断等待是否真实发生。

## 影响

实际等待由 `Thread.sleep` 执行，节奏闸门仍会阻塞任务线程；提示时机和文案无法区分“刚完成等待”与“间隔已经满足、没有额外等待”两种情况。

## 处理结果

提示分为等待开始、等待结束和无需额外等待三种状态，并补充假时钟下的顺序、总时长和停止行为测试。
