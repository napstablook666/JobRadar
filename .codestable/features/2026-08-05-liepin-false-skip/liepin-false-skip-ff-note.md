---
doc_type: feature-ff-note
feature: liepin-false-skip
date: 2026-08-05
requirement: AI明确判定不匹配的岗位自动发送平台默认沟通语
tags: [liepin, ai, delivery, preset-greeting]
---

## 做了什么
- AI 返回 `false` 的岗位自动进入平台聊天，等待页面新增本人消息后记为成功。
- AI 正常生成话术、接口异常、空结果、未读取到 JD 和 AI 关闭时保持原有分流。
- 平台默认沟通语通过实时通道下发，不再等待不存在的 HTTP 发送响应。

## 改了哪些
- `src/main/java/com/getjobs/worker/liepin/Liepin.java` — false 分支使用聊天窗口本人消息确认；AI 话术在聊天输入框发送；兼容当前抽屉式关闭按钮。
- `src/test/java/com/getjobs/worker/liepin/LiepinAiGreetingDecisionTest.java` — 覆盖 `false`、正常文本、空结果和代码围栏文本。

## 怎么验证的
- `./gradlew.bat compileJava test --tests com.getjobs.worker.liepin.LiepinAiGreetingDecisionTest --tests com.getjobs.worker.liepin.LiepinMessageRequestTest --no-daemon` → BUILD SUCCESSFUL
- 真实复测：AI 返回 `false` 后日志出现“猎聘平台默认沟通语已确认”，随后出现“已发送猎聘预设语”，最终状态为“投递任务完成，共发起1个聊天”。
- 首次页面变体暴露头部选择器超时后已修正；第二次真实复测完成闭环并主动停止。

## 对 `.codestable/` 的影响
- 更新本次快改记录，补充实时通道确认方式和真实复测证据；不改变既有规格或历史事项。
