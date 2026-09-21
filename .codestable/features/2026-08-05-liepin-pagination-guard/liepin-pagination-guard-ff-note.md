---
doc_type: feature-ff-note
feature: liepin-pagination-guard
date: 2026-08-05
requirement: 搜索结果没有分页控件时仍可继续处理岗位
tags: [liepin, pagination, playwright, delivery]
---

## 做了什么
- 移除把 .list-pagination-box 当作搜索成功条件的硬等待。
- 搜索结果以岗位卡片或任一分页控件出现为准；单页结果没有分页时按当前页处理。
- 下一页处理支持 .list-pagination-box、.ant-pagination 和 ul.ant-pagination。

## 改了哪些
- src/main/java/com/getjobs/worker/liepin/Liepin.java — 增加搜索结果等待和分页控件兼容查找，并在每个关键词开始时重置页数。

## 怎么验证的
- .\gradlew.bat test --no-daemon → BUILD SUCCESSFUL，退出码 0。
- git diff --check -- src/main/java/com/getjobs/worker/liepin/Liepin.java → 空输出，退出码 0。
- 重启后端后用单关键词、单岗位上限、0-0 秒间隔真实复测；API 返回任务启动成功，确认平台话术后最终状态为 投递任务完成，共发起1个聊天。
- 复测结束后配置已恢复为 18 个关键词、AI 开启、上限 30、间隔 2-5 秒，登录状态保持为已登录。

## 对 .codestable/ 的影响
- 新增本次快改记录，不改变既有规格或历史事项。
