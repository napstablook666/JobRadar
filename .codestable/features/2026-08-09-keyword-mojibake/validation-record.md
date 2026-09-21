# APP 搜索关键词乱码验证记录

## 基线与改动

- 快照：`target/codex-git/checkpoints/20260809-160107-liepin-keyword-mojibake`
- 明确改动路径、基线哈希和实现后哈希记录在同目录的 `baseline-hashes.txt`、`modified-hashes.txt`。
- 变更字段：配置关键词 `keywords`；运行时解析、页面文本框/断点关键词显示和保存规范化共用同一 UTF-8 乱码还原规则。
- 数据库原值保持不动，下一次保存写入规范 JSON。

## 源码与测试

- `.\gradlew.bat test --tests com.getjobs.worker.utils.KeywordParserTest --tests com.getjobs.worker.liepin.LiepinPageProgressTest --tests com.getjobs.application.service.LiepinServiceKeywordNormalizationTest --no-daemon`
  - `BUILD SUCCESSFUL`
- `.\gradlew.bat test --no-daemon`
  - `202 tests completed, 0 failed, 0 errors, 0 skipped`
  - `BUILD SUCCESSFUL`
- `pnpm lint`（工作目录 `front`）
  - exit code `0`
- `pnpm build`（工作目录 `front`）
  - exit code `0`
- `git diff --check`
  - exit code `0`

## 运行态回归

- 真实页面回读历史乱码配置后，关键词文本框显示 `18` 个正常中文关键词。
- 页面截图：`target/codex-git/keyword-mojibake-ui.png`。
- 浏览器会话已关闭；`agent-browser session list` 为空，专用 Chrome 进程已清理。

## 产物回放

- `git apply --check --whitespace=nowarn keyword-mojibake.patch`（隔离基线目录）
  - exit code `0`
- 应用补丁后逐项 SHA-256 与 `modified-hashes.txt` 比对
  - `10/10` paths match
- `git apply --reverse --check --directory=.codestable/features/2026-08-09-keyword-mojibake/patch-check/workspace --whitespace=nowarn keyword-mojibake.patch`
  - exit code `0`
- 当前工作区逐项 SHA-256 与 `modified-hashes.txt` 比对
  - `10/10` paths match
- `git diff --check`
  - exit code `0`（仅输出工作区既有换行转换提示）
- `powershell -NoProfile -File rollback-keyword-mojibake.ps1 -RepositoryRoot <rollback-check>`
  - exit code `0`
- 回滚后逐项 SHA-256 与 `baseline-hashes.txt` 比对，新增测试和 issue 均已移除
  - `8/8` baseline paths match; `2/2` new paths absent

## 结论

历史重复转码关键词在页面显示、断点匹配和搜索运行时均恢复为规范文本；配置再次保存时会收敛为规范 JSON，补丁和回滚产物可独立重放。
