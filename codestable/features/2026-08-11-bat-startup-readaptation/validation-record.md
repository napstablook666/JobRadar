# BAT 启动链重新适配验证记录

## 基线

- checkpoint：`target/codex-git/checkpoints/20260811-084438-bat-startup-readaptation-20260811`
- baseline HEAD：`e56e1d3`
- 变更清单：`modified-artifacts.txt`

## 修改

- 前端启动器直接运行本地 Next CLI，不再经过 `npx/pnpm` 依赖预检。
- PowerShell 启动器检查 Node 与本地 Next 依赖，捕获前后端子进程退出状态，并按服务就绪结果返回退出码。

## 验证

- `node --check front/start-dev.mjs`：退出码 `0`。
- PowerShell AST 解析：`PS_PARSE=0`。
- `git diff --check`：退出码 `0`。
- 本地 Next 依赖检查：`NEXT_LOCAL=True`。
- 根目录 `start.bat -NoBrowser`：前端约 7 秒就绪、后端约 12 秒就绪，`START_EXIT=0`。
- 从 `C:\Windows` 启动：`START_FROM_WINDOWS=0 FRONT_HTTP=200 API_HEALTH=200`。
- 从 `C:\Windows` 停止：`STOP_FROM_WINDOWS=0 PORTS_AFTER=False`。
- 生成 `run-front.bat`：`HAS_PNPM=False HAS_NPX=False HAS_NODE=True RUN_FRONT_LONE_LF=0`。

## 回滚

- 脚本：`rollback-bat-startup-readaptation.ps1`。
- 脚本先执行 `git apply --reverse --check --unidiff-zero`，检查通过后再应用反向 patch。
