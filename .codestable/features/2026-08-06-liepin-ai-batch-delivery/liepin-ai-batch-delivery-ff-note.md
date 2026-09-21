---
doc_type: feature-ff-note
feature: liepin-ai-batch-delivery
date: 2026-08-06
requirement: 猎聘 AI 评分投递批量提速与模板化输出
tags: [liepin, ai, batch, scoring, cache, delivery]
---

## 做了什么
- 增加 MANUAL、SINGLE_AUTO、BATCH_SHADOW、BATCH_AUTO 四种投递模式，旧 autoAiDelivery 自动映射到 SINGLE_AUTO。
- 当前页先收集岗位，再按批量大小请求一次评分和一次话术，按 jobId 回填，减少逐岗位 AI 请求。
- 增加评分缓存和话术缓存；候选人资料、岗位内容、模型和模板变化会生成新的缓存键。
- 批量评分和话术都要求结构化 JSON；评分分数、决策、理由码、理由长度和话术单行长度均经过后端校验。
- BATCH_SHADOW 只评分和生成话术，不发送；BATCH_AUTO 通过评分、话术和已有发送结果闸门后才发送。
- AI 配置页增加评分模板和通过岗位话术模板，支持命名变量，保留原有五个 `%s` 逐岗模板。
- 猎聘页面增加模式、批量大小、AI 通过分数、复核分数和本轮请求/缓存指标。

## 关键字段
- `ai.screen_prompt`：批量评分模板。
- `ai.message_prompt`：批量话术模板。
- `liepin_config.ai_delivery_mode`：投递模式。
- `liepin_config.ai_batch_size`：单批岗位数，运行时收敛到 3-10。
- `liepin_config.ai_min_score` / `ai_review_min_score`：通过和复核分数。
- `liepin_ai_screen_cache` / `liepin_ai_message_cache`：按岗位与输入哈希缓存。

## 验证边界
- Java 测试覆盖模板变量、严格解析、模式兼容和已有发送路径。
- 本轮没有启动新的自动投递任务；运行态已有任务保持原状。
- 全量前端 ESLint 仍受仓库既有规则错误影响，猎聘页和 AI 配置页定向 lint 通过。
