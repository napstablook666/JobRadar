---
doc_type: feature-ff-note
feature: liepin-ai-resume
date: 2026-08-05
requirement: AI生成话术发送成功后自动发送猎聘简历
tags: [liepin, ai, resume, delivery]
---

## 做了什么
- AI话术在聊天窗口确认发送后，自动执行“发简历”→“立即投递”。
- 只有聊天窗口新增本人简历消息时才记录简历发送成功；弹窗或消息确认失败会保留已确认的话术结果并关闭残留弹窗。

## 改了哪些
- `src/main/java/com/getjobs/worker/liepin/Liepin.java` — 增加简历动作定位、确认弹窗处理、消息数量校验和发送结果汇总。
- `src/main/java/com/getjobs/worker/liepin/Locators.java` — 集中维护“发简历”和“立即投递”文本定位常量。

## 怎么验证的
- `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon` → `BUILD SUCCESSFUL`。
- 真实单岗位复测：状态出现 `aiAvailable=true`，确认 `ai` 后岗位状态为已投递；聊天窗口实际同时出现 AI招呼语、`这是我的简历`、`在线简历` 和 `附件简历`。
- 临时测试配置已恢复为原始 18 个关键词、AI开启、单次上限30、随机等待2-5秒；浏览器调试会话与残留进程已清理。

## 对 `.codestable/` 的影响
- 新增本次快改记录；不改变既有规格或历史事项。
