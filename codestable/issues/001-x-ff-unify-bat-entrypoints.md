---
type: ff
status: closed
title: BAT 运维入口三件套
---

# 做了什么

根目录运维入口从四个重复入口收敛为 start.bat、restart.bat、stop.bat。

# 改了哪些

- 移除一键启动.bat、一键停止.bat 两个重复入口。
- 新增 restart.bat，先安静停止，再转发启动参数启动服务。
- 更新启动提示和进度白板。
- 保留 bin/kill-services.bat 与 gradlew.bat。

# 怎么验证

已完成脚本静态检查、隔离副本顺序与参数验证、PowerShell 解析、行尾检查、补丁检查和回滚验证。
真实重启后前端返回 HTTP 200；后端标准 bootRun 受既有 PlaywrightManager.java 编译错误影响，已用原有编译产物恢复 8888，最终状态为 IDLE。

# 对 codestable/ 的影响

已新增本次快改记录；当前项目规格无变化。
