---
kind: issue
title: "猎聘 flag=0 搜索响应跳过"
type: ff
status: closed
created: 2026-08-11
epic: ""
---

# 猎聘 flag=0 搜索响应跳过

## 做了什么

将精确的 `{"flag":0}` 搜索响应从整轮暂停分流为当前关键词跳过，继续处理后续关键词。

## 改了哪些

- `Liepin.java` — 保留传输和风控暂停语义，仅分流单字段 `flag=0` 响应。
- `LiepinSearchResponseTest.java` — 覆盖首个关键词失败、后续关键词继续完成。

## 怎么验证的

JDK 21 下的 `LiepinSearchResponseTest` 与 `LiepinHttpSearchClientTest` 通过，`git diff --check` 通过。

## 对 codestable/ 的影响

- 无已记录真相受影响；这是搜索故障收敛边界的实现修正。
