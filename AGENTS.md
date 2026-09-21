# JobRadar 项目规则

## Git 自动管理

本仓库已启用 Codex 本地 Git 管理。目标是让每次改动可追踪、可回退，同时不吞掉工作区里原有的改动。

### 每次任务开始

1. 先运行 `powershell -NoProfile -File codestable/tools/codex-git.ps1 inspect`。
2. 在第一次编辑前运行 `powershell -NoProfile -File codestable/tools/codex-git.ps1 checkpoint -Label <task-slug>`。
3. 把快照目录记在进度或最终记录里；快照保存基线提交、状态、未跟踪文件和 diff。

### 修改边界

- 快照前已经变脏的文件属于用户或历史工作，保持原样，不自动 stage、commit、restore、reset 或删除。
- 只用 `apply_patch` 或等价的可审查编辑；不要用 `git add -A`、`git commit -am`、`git clean`。
- 不把 `db/`、`target/`、`browser-data/`、日志、Cookie、`.env`、密钥和凭证加入提交。
- 普通源码、测试、项目规则和 CodeStable 记录可以进入本地提交。

### 任务完成

1. 跑与改动匹配的测试或检查，并确认 `git diff --check`。
2. 只提交快照之后新增或从干净状态变更的明确路径：
   `powershell -NoProfile -File codestable/tools/codex-git.ps1 commit -CheckpointPath <path> -Message "<type>(<scope>): <中文动词短句>" -Path <file>...`
3. 提交后再次运行 `inspect`，把 commit 短哈希和验证结果写进 `进度白板.md` 或对应 issue。
4. 默认只创建本地 commit；`push`、改写历史、删除文件、跨模块回退和数据库操作仍需用户明确确认。

### 回退

- 优先用 `rollback -Commit <sha> -Confirm ROLLBACK` 生成反向提交，不直接改写历史。
- 回退前必须确认工作区干净且目标 commit 属于本次 Codex 任务；有冲突先停下记录，不覆盖用户改动。

脚本入口：`codestable/tools/codex-git.ps1`。
