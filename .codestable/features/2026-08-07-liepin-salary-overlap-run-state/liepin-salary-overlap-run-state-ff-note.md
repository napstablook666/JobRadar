---
doc_type: feature-ff-note
feature: liepin-salary-overlap-run-state
date: 2026-08-07
requirement: 猎聘暂停重启状态隔离与月薪交集筛选
tags: [liepin, salary, delivery, run-state]
---

## 做了什么
- 薪资统一换算为月薪 K；年薪文本按 12 薪等价换算，明确“万元/月”仍按月薪处理。
- 配置范围与岗位范围按交集判断，跨过上下界但存在交集的岗位进入 JD、相关度和 AI 筛选。
- 每轮投递增加递增 `runId`，前端忽略旧轮次轮询结果并展示本轮岗位筛选汇总。

## 改了哪些
- `LiepinService`：年薪/月份识别、薪资交集闸门。
- `Liepin`：扫描、薪资放行和跳过统计，并补齐跳过原因记录。
- `LiepinJobService`、`LiepinController`、猎聘配置页：轮次状态、汇总接口和页面展示。
- 薪资解析与任务状态回归测试。

## 怎么验证
- `gradlew.bat test --tests com.getjobs.application.service.SalaryParseCharacterizationTest --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest --no-daemon` → BUILD SUCCESSFUL。
- `gradlew.bat test --no-daemon` → BUILD SUCCESSFUL。
- `front: npm run lint -- app/liepin/page.tsx` → 0 errors / 0 warnings。
- `front: npm run build` → production build successful。

## 对 `.codestable/` 的影响
- 新增本次薪资筛选与任务轮次快改记录；不关闭历史事项，不提交或推送。
