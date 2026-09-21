# 猎聘混合读取验证记录

## 基线与产物

- 基线和最终 SHA-256：baseline-hashes.txt
- 本轮差异：liepin-hybrid-read.patch
- 可运行回滚：rollback-liepin-hybrid-read.ps1
- 工作区已有的浏览器后台模式、懒页面和平台页面生命周期改动已保留。

## 实现结论

- hybrid 下，猎聘搜索 JSON 先走 Java HttpClient。
- HTTP 请求复用浏览器上下文 Cookie，并生成运行时 trace/session 字段。
- HTTP 成功只负责岗位数据读取，页面仍负责 DOM、分页控件和投递动作。
- HTTP 非 2xx、空正文、无效 JSON、风控信号或页面加载异常均回退原 Playwright 响应链路。
- 51job 保留浏览器搜索，并与猎聘暴露统一的 readRequest 状态结构。
- 端点和请求超时可由 GETJOBS_LIEPIN_SEARCH_ENDPOINT、GETJOBS_LIEPIN_SEARCH_TIMEOUT_MS 覆盖。

## 自动化验证

命令：

$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon

字面结果：

BUILD SUCCESSFUL

169 tests completed, 0 failed

命令：

git diff --check

字面结果：

无差异错误；仅有工作区既有换行提示。

命令：

git apply --check --directory=.codestable/features/2026-08-08-liepin-hybrid-read/patch-check-2 .codestable/features/2026-08-08-liepin-hybrid-read/liepin-hybrid-read.patch

字面结果：

PATCH_APPLY_OK

命令：

patch-check-2 与工作区目标文件 SHA-256 对比

字面结果：

PATCH_REPLAY_HASHES_MATCH

命令：

.\.codestable\features\2026-08-08-liepin-hybrid-read\rollback-liepin-hybrid-read.ps1 -VerifyOnly

字面结果：

ROLLBACK_CHECK_OK

## 已知提示

- Java 编译仍保留既有 Job51.java 的 JsonNode.fields() 弃用提示。
- 本轮使用本地 HttpServer 覆盖请求体、Cookie/XSRF、非 2xx、空正文和风控回退；真实平台端点仍由运行时 Cookie 和平台字段决定。
