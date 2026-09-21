# APP 外部申请与详情验证验证记录

## 基线

- checkpoint：`target/codex-git/checkpoints/20260810-210924-app-external-verification`
- 当前工作树在 checkpoint 前已存在混合改动；本批次没有覆盖旧路径。
- 变更清单：`modified-artifacts.txt`。

## 修改

- 外部申请字段、数据库兼容迁移、解析、统计、列表、状态筛选和手动完成接口已落地。
- 普通批量与 AI 批量均识别外部申请链接；详情访问验证进入可见窗口，Cookie 只回写当前运行时，完成后按游标恢复。
- 51job 页面展示外部链接、申请状态和手动完成操作。

## 验证

- JDK 21 下 `./gradlew.bat test`：`BUILD SUCCESSFUL`，退出码 `0`。
- `front/node_modules/.bin/eslint.cmd .`：退出码 `0`。
- `front/node_modules/.bin/next.cmd build`：编译、TypeScript、14 个静态页面通过，退出码 `0`。
- `git diff --check`：退出码 `0`。
- 差异前向 `git apply --check`：退出码 `0`。
- 差异反向 `git apply --reverse --check`：退出码 `0`。
- 隔离副本：前向应用 `0`、回滚脚本 `0`、回滚后目标文件哈希比对 `0`。

## 回滚

- 脚本：`rollback-app-external-verification.ps1`。
- 运行：`powershell -NoProfile -File ... -Root ROOT`；脚本先反向检查，再应用反向差异。
