---
doc_type: feature-ff-note
feature: liepin-stop-delivery
date: 2026-08-07
requirement: 停止猎聘投递时立即结束可轮询等待并准确反馈任务状态
tags: [liepin, stop, playwright, status]
---

## 做了什么

- 岗位卡片、搜索结果、聊天消息、AI输入和发简历弹窗等待统一响应停止标志。
- 停止中的页面按钮禁用并显示“停止中...”，状态轮询缩短到250毫秒。
- 重载主端口服务，消除旧进程继续使用旧 class 的运行态漂移。

## 改了哪些

- `src/main/java/com/getjobs/worker/liepin/Liepin.java`
- `front/app/liepin/page.tsx`
- `进度白板.md`

## 怎么验证的

- 定向 Java 测试：`BUILD SUCCESSFUL`，退出码 `0`。
- Java 全量测试：`BUILD SUCCESSFUL`，退出码 `0`。
- 猎聘页面 ESLint：空标准输出，退出码 `0`。
- Next.js 生产构建：`Compiled successfully`，退出码 `0`。
- 真实接口：启动后立即停止，`288ms` 内得到 `taskState=PAUSED`、`isRunning=false`、`lastDeliveredCount=0`。

## 对 `.codestable/` 的影响

- 新增本次快改记录；未修改项目规格和投递参数。
