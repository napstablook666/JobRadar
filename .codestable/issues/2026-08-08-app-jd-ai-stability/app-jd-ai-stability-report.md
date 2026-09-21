---
doc_type: issue-report
issue: app-jd-ai-stability
status: fixed
severity: high
root_cause_type: response-shape-and-snapshot-persistence
tags: [51job, jd, ai, playwright, regression]
---

# 岗位 JD 与 AI 回复失败

## 现象

51job AI 逐岗位流程反复出现“岗位 JD 获取失败”和“AI 生成回复失败”，失败原因在页面上不可区分，岗位快照也无法稳定复用已获取内容。

## 根因

- 搜索接口解析只覆盖少数固定列表路径，嵌套岗位对象和 `items/list` 变体的 `jobId` 可能丢失。
- 搜索快照虽然解析出 `job_description`，新岗位插入 SQL 却没有写入该列。
- 详情页选择器缺少常见的 `.job-sec-text` 结构，嵌入 JSON 和正文回退也缺少统一校验。
- AI 兼容层可能返回 Responses API 的 `output[].content[].text`、JSON 对象或数组；旧提取逻辑会把这些结果判成空回复。
- AI 失败结果固定记录两次尝试，实际只请求一次时状态信息失真。

## 处理结果

搜索快照现在统一提取列表、嵌套岗位 ID、JD、链接和公司/招聘方字段，新岗位直接持久化 JD；旧快照只补齐缺失 JD/链接，不触碰投递状态。详情页增加多组 JD 选择器、嵌入 JSON 和正文分段回退。AI 响应支持文本块、Responses 输出块、对象、`items` 和顶层数组，并按真实尝试次数记录原因码。

