---
doc_type: feature-ff-note
feature: liepin-analysis-total
date: 2026-08-05
requirement: 分析页首次进入显示完整岗位池总数
tags: [liepin, analytics, kpi, filter]
---

## 做了什么
- 分析页首次进入时取消默认“已投递”状态筛选，首张卡显示完整岗位池。
- 用户手动应用状态后，统计和列表继续按当前筛选结果工作。

## 改了哪些
- `front/app/liepin/analysis/AnalysisContent.tsx` — 将 `statuses` 初始值设为空数组并同步说明。
- `进度白板.md` — 回写新的默认统计口径。
- 保存基线、补丁、验证记录和回滚脚本到本目录。

## 怎么验证
- 前端 ESLint：0 errors，保留 3 个既有 warning。
- `pnpm run build`：Next.js 生产构建成功，退出码 `0`。
- 运行接口：全量统计 `total=3388`，已投递筛选 `total=21`；两者均与列表总数一致。
- 隔离副本执行回滚脚本并通过基线哈希校验。

## 对 `.codestable/` 的影响
- 已同步进度白板；现有投递、接口和数据库结构保持原样。
