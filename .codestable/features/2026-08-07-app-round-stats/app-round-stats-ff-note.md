---
doc_type: feature-ff-note
feature: app-round-stats
date: 2026-08-07
requirement: 本轮岗位筛选与本轮 AI 处理实时显示
tags: [app, ai, delivery, runtime, stats]
---

## 做了什么
- 任务运行期间实时发布岗位筛选与 AI 处理快照，页面轮询可立即显示增量。
- 任务完成、暂停或异常后保留本轮最终统计，下一轮启动时重置为完整零值结构。
- AI 统计覆盖手动、单岗位自动、批量预演和批量自动模式。

## 改了哪些
- `src/main/java/com/getjobs/worker/liepin/Liepin.java` — 增加统计变化回调，并在扫描、AI 和发送计数变化时发布快照。
- `src/main/java/com/getjobs/worker/service/LiepinJobService.java` — 接收实时快照并暴露给状态接口。
- `front/app/liepin/page.tsx` — 增加完整统计默认值，兼容运行中和结束后的状态回读。
- `src/test/java/com/getjobs/worker/service/LiepinJobServiceDeliveryTest.java` — 增加运行中快照回归测试。

## 怎么验证
- `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot .\gradlew.bat test --no-daemon` → `BUILD SUCCESSFUL`。
- `npx eslint app/liepin/page.tsx` → 退出码 `0`。
- `npm run build` → Next.js production build successful。
- `git diff --check` → 退出码 `0`。

## 对 `.codestable/` 的影响
- 已同步 `进度白板.md`；数据库结构、发送流程和筛选规则保持现状。
- 主端口旧进程保持运行，待当前任务结束后再重载验证新代码。
