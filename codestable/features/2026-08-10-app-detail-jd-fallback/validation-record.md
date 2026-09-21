# APP 详情 JD 回退验证记录

## 基线

- `HEAD=9381bfd`。
- checkpoint 命令：`powershell -NoProfile -File codestable/tools/codex-git.ps1 checkpoint -Label app-detail-jd-fallback`
- 字面输出：`D:\Codeg 部署\get_jobs\target\codex-git\checkpoints\20260810-141145-app-detail-jd-fallback`
- 退出码：`0`

## 修改

- 输入：`Job51Service.java`、`Job51.java`、`Job51SearchJsonTest.java`。
- 修改内容：解析 `jobDescribe`；缓存 JD 先进入 AI；详情失败时走搜索页回退；增加解析回归测试。
- 编辑工具：`apply_patch`，三次源码/测试编辑均返回 `{}`，退出码 `0`。
- SHA-256：`Job51Service` `DDAF715717F8D12F6388D57C2EB16166FC7D68ED0C62B8416C68EF5F733DE128` -> `9B31C2E887EC9081874415189CB7338BEA24D0148DA3254E7C7960A327B4B85A`；`Job51` `7A18645DDB72367A41AA1E78D9CC3B0BF26708B101507C7F0061F6AAE0C42189` -> `EF70C2E09926F89A911AD5C19B284670FFA93EBA3A1B0AD4538E0F12E7F03B64`；测试 `BC9EC3563E6F6A5DB84A94ACC4E7CCC85E1F637B753F21DDFB7C6F8018FC8F22` -> `71B1CE2AEB3F2B1AB6941C4ABC9690E1902817DC8C0369B3D80A63935219E87F`。

## 验证

- 定向：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests com.getjobs.application.service.Job51SearchJsonTest --tests com.getjobs.worker.job51.Job51BehaviorTest`。
  - 字面结果：`BUILD SUCCESSFUL in 36s`。
  - 退出码：`0`。
- 全量：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test`。
  - 字面结果：`BUILD SUCCESSFUL in 15s`；测试报告统计 `34` 个 suite、`220` 个测试。
  - 退出码：`0`。
- `git diff --check`：退出码 `0`。
- `git apply --reverse --check -- codestable/features/2026-08-10-app-detail-jd-fallback/job51-detail-jd.patch`：退出码 `0`。
- 隔离副本回滚：脚本退出码 `0`，回滚后 `git diff --check` 通过，前向 patch 检查通过。
- 浏览器清理：`agent-browser close --all` 输出 `No active sessions`；会话列表为空。
- 临时证据：三份目标文件均输出 `REMOVED=...`，退出码 `0`。

## 范围

- 未修改数据库结构、公共接口或前端契约。
- 源码路径在 checkpoint 前已有历史未提交改动，保留在工作区；本记录只覆盖本轮新增行为。
