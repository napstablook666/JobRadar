---
kind: issue
title: "51job AI 回复无效与请求重试修复"
type: ff
status: closed
created: 2026-08-12
epic: ""
---

# 51job AI 回复无效与请求重试修复

## 根因

- 生产日志中的 AI 请求失败包含 `SSLHandshakeException: Remote host terminated the handshake`；历史请求还出现 HTTP `424 Upstream authentication failed`。
- `AiService` 之前把 HTTP 非 2xx 和传输异常混成普通 `RuntimeException`，`Job51` 只按异常文本排除少数 4xx，导致错误分类不准确。
- 51job AI 话术请求最多只尝试两次，固定等待 300ms，TLS、424、429 和 5xx 没有按网络瞬态失败处理。

## 改动

- `AiService` 新增 `AiRequestException`，保留状态码、端点和脱敏响应摘要；TLS、超时等传输异常也统一携带原始 cause。
- 51job AI 话术最多进行 3 次总尝试；第 1 次失败等待 1 秒，第 2 次失败等待 2 秒。
- 仅重试网络错误、TLS 握手失败、超时、HTTP 424、429 和 5xx；400/401/403/404/422 等请求拒绝不重试。
- 保留现有提示词校验、岗位跳过和不发送平台预设语的业务规则。

## 验证

- 使用本机 JDK 21 执行：
  `gradlew.bat test --tests com.getjobs.application.service.AiPromptTest --tests com.getjobs.worker.job51.Job51BehaviorTest`
- 结果：`BUILD SUCCESSFUL`。
- 全量 Java 回归：`gradlew.bat test`，结果：`BUILD SUCCESSFUL`。
- 额外覆盖：结构化异常脱敏、TLS/超时/424/429/5xx/请求拒绝分类，以及 1/2 秒退避值。
- 本次 Git checkpoint：`target/codex-git/checkpoints/20260812-142518-ai-request-retry`。

## 对 codestable 的影响

无。未清理历史日志，未处理已有 API Key 明文日志问题，未改变岗位筛选、投递和跳过规则。
