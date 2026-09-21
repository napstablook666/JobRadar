---
type: ff
status: closed
title: 猎聘投递吞吐提升
---

# 做了什么

提升猎聘自动投递默认吞吐，缩短搜索、翻页、详情和发送节奏，并允许满足评分阈值的 REVIEW 结果进入批量投递。

# 改了哪些

- 单轮默认上限调整为 50；搜索、翻页、详情和发送等待范围调整为更短区间。
- 每成功投递 15 次后执行 60 到 120 秒冷却。
- 批量 AI 投递接受 PASS >= 70 或 REVIEW >= 60，并继续排除 HARD_MISMATCH。
- 修正范围最大值解析，保留合法的低于默认值的最大值。

# 怎么验证的

- JDK 21 下运行 `./gradlew.bat test --tests com.getjobs.worker.liepin.LiepinConfigTest --tests com.getjobs.worker.liepin.LiepinRateGuardTest --tests com.getjobs.worker.liepin.LiepinAiBatchResponseTest`：`BUILD SUCCESSFUL`。
- `git diff --check`：退出码 `0`。
- 代码提交：`99add4a`（`feat(liepin): 提升投递吞吐配置`）。

# 对 codestable/ 的影响

新增本任务记录；不改变 CodeStable 工具行为。
