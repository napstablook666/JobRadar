---
doc_type: feature-ff-note
feature: liepin-ai-auto-delivery
date: 2026-08-06
requirement: 猎聘加入 AI 筛选评分自动投递开关
tags: [liepin, ai, scoring, delivery]
---

## 做了什么
- 新增“AI筛选自动投递”开关，默认关闭；关闭时继续使用原来的逐岗位人工确认。
- 自动模式要求 AI 返回严格 JSON 评分、理由和招呼语，评分达到最低分数才进入已有聊天发送和简历发送流程。
- 自动模式缺少 JD、评分格式异常、AI 请求失败或评分低于阈值时跳过；不再先受旧硬编码相关度关键词限制。
- 新增最低评分配置，默认 70，范围固定为 0-100；开启自动投递时关闭 AI 招呼语会被后端拦截。

## 改了哪些
- `LiepinConfig`、`LiepinConfigEntity`、`ConfigService`、`LiepinService`：新增开关、阈值和兼容迁移。
- `Liepin`、`LiepinAiAssessment`：评分提示词、严格解析、自动分支和评分状态消息。
- `front/app/liepin/page.tsx`：新增开关与最低评分控件。
- `LiepinAiAssessmentTest`、`LiepinConfigTest`：补充解析、边界、默认值和阈值回归。

## 验证结论
- Java 全量测试通过。
- 猎聘页面 ESLint 通过；Next.js 生产构建通过。
- 运行态配置回读为 `autoAiDelivery=0`、`aiMinScore=70`，任务空闲。
- 本轮只验证配置、解析和模拟分支，没有开启真实自动投递。

## 回滚口径
- 使用 `rollback-liepin-ai-auto-delivery.ps1` 恢复本 feature 修改前的代码和进度白板。
- 数据库新增列保留为空闲兼容列，不删除业务数据；补丁文件为 `liepin-ai-auto-delivery.patch`。
