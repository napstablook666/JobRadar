---
doc_type: feature-ff-note
feature: liepin-salary-guard
date: 2026-08-05
requirement:
tags: [liepin, salary, delivery, guard]
---

## 做了什么
修复猎聘搜索条件被忽略时仍会投递超出薪资范围岗位的问题。

## 改了哪些
- `src/main/java/com/getjobs/application/service/LiepinService.java` — 支持解析 `20-40K` / `20K-40K`，新增 `6$10` 范围码解析和完整区间校验；日薪、面议、缺失或无法解析的薪资拒绝通过。
- `src/main/java/com/getjobs/worker/liepin/Liepin.java` — 投递前校验配置和岗位薪资；接口数据缺失或岗位超范围时记录原因并跳过，不再点击“聊一聊”。
- `src/test/java/com/getjobs/application/service/SalaryParseCharacterizationTest.java` — 覆盖范围内、上下界越界、日薪、面议、缺失薪资和无效配置。
- `进度白板.md` — 记录本地拦截已完成，真实投递复测仍待授权。

## 怎么验证的
- `JAVA_HOME=...jdk-21 .\gradlew.bat test --tests com.getjobs.application.service.SalaryParseCharacterizationTest --no-daemon` → BUILD SUCCESSFUL。
- `JAVA_HOME=...jdk-21 .\gradlew.bat test --no-daemon` → BUILD SUCCESSFUL。
- 未启动真实猎聘投递，未产生新的“聊一聊”副作用。

## 对 `.codestable/` 的影响
- 新增本次薪资拦截快改记录，不改变既有规格或历史事项。
