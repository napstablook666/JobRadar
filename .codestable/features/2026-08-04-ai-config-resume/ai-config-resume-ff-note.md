---
doc_type: feature-ff-note
feature: ai-config-resume
date: 2026-08-04
requirement:
tags: [ai, config, resume, boss]
---

## 做了什么
根据张涵的应用物理学简历，更新 AI 配置页面中的技能介绍和 AI 提示词。

## 改了哪些
- 通过现有 `POST /api/ai/config` 更新数据库中的 `ai.introduce` 和 `ai.prompt`。
- 技能介绍补充专业课程、放疗中心实习、Elekta 直线加速器、Siemens CT 模拟定位、呼吸门控、SGRT、现场流程和求职方向。
- 提示词改为放疗设备/医疗器械技术支持方向，要求只生成真实、简洁的 HR 打招呼语，并保留 5 个 `%s` 占位符。
- 未修改页面源码、Boss `sayHi`、投递状态或登录 Cookie。

## 怎么验证
- `POST /api/ai/config` 返回 `success: true`。
- `GET /api/ai/config` 回读确认两个字段与目标内容一致。
- 提示词以 `您好，我是应用物理学应届生，%s。` 开头，`%s` 数量为 5。

## 对 `.codestable/` 的影响
- 仅新增本次快改记录，不改变现有规格或历史事项。
