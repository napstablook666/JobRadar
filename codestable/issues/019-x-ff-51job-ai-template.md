---
kind: issue
title: "51job AI 模板拒绝回复修复"
type: ff
status: closed
created: 2026-08-11
epic: ""
---

# 51job AI 模板拒绝回复修复

## 做了什么

- 共享招呼语模板不再允许把 `false` 作为岗位拒绝结果；51job 遇到裸 `false` 会按格式错误重试一次。
- 当前 AI 配置模板已做最小替换，保留原有岗位与事实约束。

## 改了哪些

- `AiService` 校验共享模板输出契约并提供无拒绝分支的默认模板。
- 配置保存接口对无效模板返回 `400`；51job 将最终失败原因记录为 `ai_response_false`。
- 数据库更新前的 SQLite 备份位于本次 Git checkpoint 目录。

## 怎么验证的

- `gradlew.bat clean test --tests com.getjobs.application.service.AiPromptTest --tests com.getjobs.worker.job51.Job51BehaviorTest`：通过。
- 模板从哈希 `5f9ba8af9417` 更新为 `ac2cd0e89128`，且不再含 `返回false` 分支。
- 本地提交：`19ff2c4`。

## 对 codestable 的影响

无影响；现有投递运行时和平台业务规则未改变。
