---
doc_type: feature-ff-note
feature: liepin-batch-send-retry
date: 2026-08-07
requirement: 修复猎聘 AI 批量筛选后一个投递都没有的问题
tags: [liepin, ai, batch, delivery, retry, locator]
---

## 做了什么

- 批量 AI 等待期间不再持有旧岗位按钮引用。
- 发送前按岗位 ID 重新查找当前卡片和“聊一聊”按钮，原卡片索引作为回退。
- 页面重定位、按钮失效、点击异常和发送确认失败都返回 `RETRY`，当前页不推进。
- 增加 AI 超时、结果无效、按钮失效和确认失败统计，并在状态接口和页面显示。
- 给 Responses API 补齐与普通 AI 请求一致的 60 秒请求上限。

## 改了哪些

- `Liepin.java`：PreparedJob、批量发送、失败状态、重定位和 AI 统计。
- `AiService.java`：统一 AI 请求超时常量并覆盖 Responses API。
- `LiepinJobService.java`：补充空统计结构。
- `front/app/liepin/page.tsx`：显示独立失败计数。
- `LiepinBatchDeliveryGuardTest.java`：补回归测试。

## 怎么验证

- Java 全量 125 测试通过，失败/错误 0。
- 猎聘页定向 ESLint、前端生产构建和 `git diff --check` 通过。
- 回滚预览和隔离副本实际回滚均通过。

## 对 `.codestable/` 的影响

- 新增本轮快改记录、基线哈希、补丁、验证记录和回滚脚本。
- 未修改项目规格；服务重载后的真实运行已确认 1 条聊天和简历发送成功。
