---
doc_type: issue-fix-note
issue: 2026-08-06-liepin-target-closed
status: fixed
root_cause_type: missing-guard
tags: [liepin, delivery, playwright, lifecycle]
---

# 投递页面生命周期异常 修复记录

## 1. 修复内容

- `PlaywrightManager` 增加 `ensureLiepinPageReady()`，检查页面、Context 和 Browser 连接状态。
- 页面关闭时创建新页面、恢复导航、重新挂载登录监控；CDP 连接失效时复用现有重连路径。
- 页面关闭事件会清空过期引用，并避免同一页面重复注册监控。
- `Liepin` 增加 `PageLifecycleException`、嵌套 TargetClosedError 识别和单次恢复回调。
- 搜索、详情、悬停、按钮查找和翻页阶段支持恢复；聊天发送阶段进入结果不确定状态，不重复发送。
- `LiepinJobService` 将底层异常转换为稳定的任务状态消息。

## 2. 验证

- `compileJava`：通过。
- `LiepinPageLifecycleTest`、`LiepinPageProgressTest`、`LiepinRateGuardTest`：通过。
- `git diff --check`：通过。
- 全量 `test --no-daemon`：通过，退出码 0。
- 回滚脚本解析和项目根目录边界检查：通过，退出码 0。

## 3. 回滚

本次没有提交 Git commit。回滚脚本只用于恢复本次新增的测试和 CodeStable 文档；Java 源文件的回退应使用本次工作区 diff 逐段审阅，避免覆盖既有未提交修改。完整命令记录见 `validation-record.md`。
