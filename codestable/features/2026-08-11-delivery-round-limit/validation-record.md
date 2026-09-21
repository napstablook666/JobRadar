# 投递上限与短间歇验证记录

## 基线

- checkpoint：`target/codex-git/checkpoints/20260811-003926-delivery-round-limit`
- 基线提交：`aa3f62e`
- 运行数据库、日志和历史运行目录保持工作区原状，未纳入本批次提交。
- 变更清单：`modified-artifacts.txt`。

## 修改

- 51job 与猎聘均支持自定义成功投递上限，`0` 表示无限。
- 达到上限后可选择停止，或按短休息区间自动恢复；两端页面回读并保存这些字段。
- 猎聘页面增加 `RESTING` 倒计时、触发原因和恢复关键词/页码展示。
- 任务服务保留恢复游标，休息期间释放平台租约，停止时取消待执行恢复任务。

## 验证

- JDK 21 下定向 Java 测试：`BUILD SUCCESSFUL`，退出码 `0`。
- JDK 21 下全量 `./gradlew.bat test`：`BUILD SUCCESSFUL`，退出码 `0`。
- `front/node_modules/.bin/eslint.cmd app/51job/page.tsx app/liepin/page.tsx`：无输出，退出码 `0`。
- `front/node_modules/.bin/next.cmd build`：`Compiled successfully`、14 个静态页面生成完成，退出码 `0`。
- `git diff --check`：退出码 `0`。
- `pnpm lint` 受到本机依赖脚本审批策略拦截；已用本地 ESLint 二进制完成同等页面检查。
- 本批次提交短哈希：`83c042a`。

## 回滚

- patch：`delivery-round-limit.patch`。
- 脚本：`rollback-delivery-round-limit.ps1`。
- 运行：`powershell -NoProfile -File ... -Root ROOT`；脚本先执行反向检查，再应用反向差异。
