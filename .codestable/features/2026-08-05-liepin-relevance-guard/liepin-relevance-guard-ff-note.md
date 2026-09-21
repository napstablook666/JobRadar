---
doc_type: feature-ff-note
feature: liepin-relevance-guard
date: 2026-08-05
requirement: AI 开启时只发送目标医疗设备方向且成功生成 AI 话术的岗位
tags: [liepin, ai, relevance, delivery, analytics]
---

## 做了什么
- 收紧猎聘 AI 投递入口：缺 JD、明显不相关、AI 返回 `false`、空结果和请求异常均直接跳过。
- 保留有效 AI 话术的原确认、发送和自动发简历链路；AI 关闭时仍保留平台预设语路径。
- 分析页默认使用 `statuses=已投递`，取消状态筛选后仍可查看完整岗位池。

## 改了哪些
- `src/main/java/com/getjobs/worker/liepin/Liepin.java` — 增加医疗设备领域相关度门槛和有效 AI 话术判定，移除 AI 不匹配时的预设语自动发送。
- `src/test/java/com/getjobs/worker/liepin/LiepinAiGreetingDecisionTest.java` — 增加 AI 结果与岗位相关度正反例。
- `front/app/liepin/analysis/AnalysisContent.tsx` — 默认状态筛选为已投递，并补齐 Chart.js 类型声明。
- `进度白板.md` — 同步本次行为和验证结论。

## 怎么验证的
- `JAVA_HOME=...jdk-21 .\\gradlew.bat test --no-daemon` → `BUILD SUCCESSFUL`。
- `pnpm exec eslint app/liepin/analysis/AnalysisContent.tsx` → 0 errors，保留 3 个既有 Hook/未使用状态 warning。
- `pnpm run build` → Next.js 生产构建成功。
- `GET /api/liepin/stats?statuses=已投递` → `total=19, delivered=19, pending=0`。
- `GET /api/liepin/list?statuses=已投递` → `total=19`；不带状态筛选的岗位池 → `total=1706`。
- 前端 ESLint：0 errors，保留 3 个既有 Hook/未使用状态 warning。

## 对 `.codestable/` 的影响
- 仅新增本次快改记录，不改变既有规格或历史投递记录。
