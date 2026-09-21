# 本次变更 Diff 摘要

基线为本会话开始时的工作区状态；已有未提交改动保持原样。本文件记录本次新增行为的最小 diff 语义。

```diff
Liepin.java
+ 增加医疗设备领域词、明显偏离岗位标题词和泛制造标题词。
+ AI 开启且 JD 缺失时直接跳过。
+ 标题/JD 未命中目标医疗设备领域时直接跳过。
+ AI 返回 false、空结果或异常时直接跳过。
- 删除 AI false -> SEND_PRESET 的自动分支。
+ 只有有效 AI 话术进入原确认、默认语确认、AI 话术和自动发简历链路。
+ 增加 isUsableAiGreeting 与 isRelevantJob 的纯规则测试入口。

LiepinAiGreetingDecisionTest.java
+ 增加有效 AI 话术、医疗设备正例、建筑/销售/泛制造反例和缺失 JD 反例。

AnalysisContent.tsx
- 首次状态筛选为空。
+ 首次状态筛选为“已投递”；取消筛选后保留完整岗位池查询能力。
+ 为 Chart.js 全局对象补齐类型，lint errors 清零。
```
