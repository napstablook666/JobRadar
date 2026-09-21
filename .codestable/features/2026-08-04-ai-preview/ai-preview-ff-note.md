---
doc_type: feature-ff-note
feature: ai-preview
date: 2026-08-04
requirement:
tags: [ai, preview, boss, resume]
---

## 做了什么
在 AI配置页增加 HR 打招呼语生成测试，用于直接查看当前简历介绍和提示词的生成效果。

## 改了哪些
- `AiConfigController.java` — 新增 `POST /api/ai/preview`，只调用 AI，不保存配置或启动投递。
- `AiService.java` / `Boss.java` — 共用 5 个 `%s` 的提示词格式化逻辑，避免预览和正式投递参数顺序不一致。
- `front/app/ai-config/page.tsx` — 新增岗位关键词、岗位名称、JD、参考语输入，生成按钮、加载/错误/结果状态和复制结果操作。
- 新增 `AiPromptTest.java`，覆盖占位符顺序、错误数量和岗位文本包含百分号的情况。

## 怎么验证
- `JAVA_HOME=JDK 21 .\gradlew.bat test --no-daemon` → BUILD SUCCESSFUL。
- `pnpm exec eslint app/ai-config/page.tsx` → 通过。
- `pnpm build` → 构建成功，`/ai-config` 页面返回 200 且包含效果预览测试区。
- 预览接口错误模板返回 400；放疗设备岗位真实调用成功，生成 HR 文本；预览前后 AI 配置 `updatedAt` 不变。

## 对 `.codestable/` 的影响
- 仅新增本次快改记录，不改变现有规格或历史事项。
