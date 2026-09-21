---
kind: issue
title: "猎聘预览岗位重复键修复"
type: ff
status: closed
created: 2026-08-11
epic: ""
---

# 猎聘预览岗位重复键修复

## 做了什么

为重复的岗位预览文本生成唯一 React key，消除列表渲染的控制台警告。

## 改了哪些

- `front/app/liepin/page.tsx` — 预览岗位标签的 key 加入数组下标。

## 怎么验证的

- `front/node_modules/.bin/eslint.cmd app/liepin/page.tsx` 与 `git diff --check` 通过。
- `http://127.0.0.1:3000/liepin` 后端返回 502，未取得可复现的预览数据；唯一键由文本与位置组成。
- 本地提交：`580eed5`。

## 对 codestable/ 的影响

- 无已记录真相受影响；这是列表渲染正确性修复。
