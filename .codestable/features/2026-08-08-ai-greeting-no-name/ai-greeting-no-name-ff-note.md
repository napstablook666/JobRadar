---
doc_type: feature-ff-note
feature: ai-greeting-no-name
date: 2026-08-08
requirement: AI 生成打招呼语固定以“您好，我是应用物理学应届毕业生，”开头，不直接说姓名
tags: [ai, greeting, prompt, validation]
---

## 做了什么

- AI 生成话术统一使用固定开头，不再输出候选人姓名或“我叫/姓名/名字是”等命名表达。
- 预览、APP、51job 和批量话术入口共用同一套格式校验；无效结果按入口原有跳过或默认语策略处理。

## 改了哪些

- `AiService.java` — 增加固定前缀、候选人姓名提取、单行/长度/Markdown/false 校验。
- `AiConfigController.java` — 预览接口返回校验后的话术，无效结果返回 `422`。
- `Boss.java`、`Job51.java`、`Liepin.java` — 发送前统一过滤无效 AI 话术。
- `AiPromptTest.java` — 覆盖固定前缀、姓名过滤、Markdown 包裹和 `false` 分支。
- `ai-config-modified.json` — 保存目标 `prompt` 与 `messagePrompt` 配置快照。

## 怎么验证

- Java 全量测试、AI 配置页 ESLint、Next.js 生产构建和 `git diff --check` 均退出码 `0`。
- 真实预览 HTTP `200`，结果长度 `35`，固定前缀、单行和姓名过滤均通过。
- 补丁应用、源码反向回滚、运行态基线回滚和目标配置恢复均已验证。

## 对 `.codestable/` 的影响

- 无已记录真相受影响；本次快改仅新增本特性产物和记录，不修改既有规格。
