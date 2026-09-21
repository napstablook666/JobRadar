---
kind: issue
title: "Codex 项目级 Git 自动管理"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# Codex 项目级 Git 自动管理

## 做了什么

给本仓库增加 Codex 专用 Git 规则和脚本，让后续任务先留基线，再只提交自己的新改动；现有脏工作树保持原样。

## 改了哪些

- `AGENTS.md` — 约束任务开始、提交、回退和敏感路径边界。
- `codestable/tools/codex-git.ps1` — 提供 `inspect`、`checkpoint`、`commit`、`rollback`。

## 怎么验证的

PowerShell 5.1 语法检查通过；快照生成成功；对快照前已有的 `Liepin.java` 提交请求被护栏拒绝；当前 `MANUAL` 试跑命中人工确认卡；三组猎聘定向 Java 测试通过。

## 对 codestable/ 的影响

- 已新增项目级 Git 工作规则和工具；未提交、未推送现有历史改动。
