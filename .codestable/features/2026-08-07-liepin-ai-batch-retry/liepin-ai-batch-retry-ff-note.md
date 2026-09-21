---
doc_type: feature-ff-note
feature: liepin-ai-batch-retry
date: 2026-08-07
requirement: 新增 AI 超时一键批量重试
tags: [liepin, ai, batch, retry, status]
---

## 做了什么

- 新增“一键批量重试”入口，后端连续处理现有 AI 超时待重试数量。
- 每轮沿用当前页断点、已发送岗位跳过、AI 重试配置和平台节奏控制。
- 待重试数量清空后自动完成；一轮无进展或收到停止、风控、页面异常信号时暂停并保留继续入口。
- 页面显示批量轮次、剩余数量和累计成功聊天数。

## 改了哪些

- `LiepinJobService`：增加批量运行模式、轮次状态、循环决策和停止感知等待。
- `LiepinController`：增加 `POST /api/liepin/retry-ai-batch`。
- `front/app/liepin/page.tsx`：增加批量按钮、状态类型和批量进度展示。
- 测试覆盖批量决策和接口启动契约。

## 怎么验证

- 定向 Java 测试、Java 全量测试、猎聘页面 ESLint 和 Next.js 生产构建均通过。
- `git diff --check` 通过。
- 补丁、基线哈希和隔离回滚验证记录见同目录文件。

## 对 `.codestable/` 的影响

- 新增本次快改记录、补丁、基线、验证记录和回滚脚本。
- 未修改项目规格和数据库结构。
