---
doc_type: feature-ff-note
feature: liepin-page-resume
date: 2026-08-06
requirement: 记住猎聘上次投递页码，下次从断点续跑
tags: [liepin, pagination, resume, delivery]
---

## 做了什么
- 按关键词 + 城市 + 薪资持久化“已完整完成页”。
- 下次同一筛选从下一页 URL 直跳续跑；半页中断不推进进度。
- 关键词全部页跑完自动清进度；配置页可查看并一键重置。

## 改了哪些
- `LiepinPageProgress.java` / `LiepinPageProgressTest.java` — 起始页计算
- `LiepinService.java` — `liepin_page_progress` 表与读写
- `Liepin.java` — 续跑循环、整页完成后写进度
- `LiepinController.java` — config 返回进度，`POST /page-progress/reset`
- `front/app/liepin/page.tsx` — 进度展示与重置按钮

## 怎么验证的
- `gradlew test --tests com.getjobs.worker.liepin.LiepinPageProgressTest` → BUILD SUCCESSFUL
- `gradlew compileJava compileTestJava` → BUILD SUCCESSFUL
