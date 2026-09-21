---
kind: issue
title: "APP AI 全自动投递真实复测"
type: ff
status: closed
created: 2026-08-09
epic: ""
---

# APP AI 全自动投递真实复测

## 做了什么

保留 AI 评分阈值 70，将 APP 投递模式切换为 `BATCH_AUTO`，使用可见登录会话运行真实实例测试，并在任务结束后恢复单次上限 10。

## 运行结果

- `runId=1`，`taskState=COMPLETED`，`isRunning=false`
- 扫描 131 个岗位，AI 通过 5 个，AI 复核 8 个，成功发起 1 个聊天
- `buttonFailures=0`、`confirmationFailures=0`、`aiTimeouts=0`
- 搜索读取走浏览器响应，登录态为 `isLoggedIn=true`

## 配置收口

- `enableAi=1`
- `autoAiDelivery=1`
- `aiDeliveryMode=BATCH_AUTO`
- `aiMinScore=70`
- `aiReviewMinScore=60`
- `maxPerRun=10`

## 验证

- Java 定向测试与全量测试：`BUILD SUCCESSFUL`，退出码 `0`
- `/api/liepin/health`：`success=true`、`status=healthy`
- `/api/liepin/status`：任务完成且投递数为 `1`
- `/api/liepin/config`：自动模式和 `maxPerRun=10` 回读一致
- 本任务基线快照：`target/codex-git/checkpoints/20260809-144223-app-ai-auto-delivery-live`

## 回滚

运行 `012-x-ff-app-ai-auto-delivery-live-rollback.ps1` 可将当前运行上限恢复到单次测试值 `1`，并校验自动模式字段不变。
