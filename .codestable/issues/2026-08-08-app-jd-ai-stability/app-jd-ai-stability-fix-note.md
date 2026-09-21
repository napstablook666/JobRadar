---
doc_type: issue-fix-note
issue: app-jd-ai-stability
status: fixed
tags: [51job, jd, ai, regression]
---

# 修复记录

## 修改

- `AiService.java`：兼容 Chat/Responses 文本块、输出块、对象和数组；结构化请求失败时降级为严格 JSON 文本请求，保留有限错误摘要。
- `Job51Service.java`：扩展搜索 JSON 列表与嵌套字段解析，并把 JD 写入新快照；旧快照仅补写缺失 JD/链接。
- `Job51.java`：统一搜索响应监听、详情页 JD 回退链路、AI 两次校验重试和原因码；失败结果记录真实尝试次数。
- `Job51Locators.java`：增加 `.job-sec-text`、详情描述和测试标识候选。
- `AiPromptTest.java`、`Job51SearchJsonTest.java`、`Job51BehaviorTest.java`：覆盖提示词、响应体、JSON 列表、嵌套岗位 ID 和回退链接。

## 行为验收

- 搜索快照可从顶层/嵌套列表取出岗位 ID、JD、链接和公司字段。
- 新岗位插入与旧岗位补写均保留投递、打招呼状态。
- Responses 输出块与顶层 JSON 数组可进入统一话术校验。
- JD 或 AI 失败会记录具体原因码，不再只显示统一失败文本。

