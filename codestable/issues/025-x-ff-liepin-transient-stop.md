---
kind: issue
title: "猎聘瞬时搜索失败误报完成"
type: ff
status: closed
created: 2026-08-12
epic: ""
---

# 猎聘瞬时搜索失败误报完成

## 做了什么

将猎聘搜索、翻页、结果加载和页面扫描的瞬时失败统一纳入当前关键词重试，连续两次额外重试仍失败时暂停任务并保留当前页进度。

## 改了哪些

- `Liepin.java` — 移除 `flag=0` 静默跳过；失败最多额外重试2次；正常空列表继续完成；失败页不推进分页进度。
- `LiepinSearchResponseTest.java` — 覆盖连续失败暂停、第三次成功、`flag=0` 重试和失败不保存页码。

## 怎么验证的

- JDK 21 下 `./gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinSearchResponseTest` 通过。
- JDK 21 下 `./gradlew.bat test --no-daemon` 通过；`git diff --check` 通过。
- 实现提交：`540f611`。

## 对 codestable/ 的影响

- 无已记录真相受影响；这是对已有搜索失败状态边界的实现修正。
