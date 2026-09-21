# 验证记录

## 基线与修改

- 工作区：`D:\Codeg 部署\get_jobs`
- 基线命令：`git diff --check`
- 修改命令：`apply_patch`，仅修改四个平台服务的执行器导入和字段默认值。
- 配置基线：`application.yaml` 中 `runtime=isolated`、`executor-size=4`、`browser.mode=background`。

## 已执行命令与结果

| 命令 | 结果 | 退出码 |
|---|---|---:|
| `$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'; .\\gradlew.bat test --tests com.getjobs.worker.service.Job51JobServiceStatusTest` | `BUILD SUCCESSFUL in 12s` | 0 |
| `$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'; .\\gradlew.bat test` | `BUILD SUCCESSFUL in 11s` | 0 |
| `git diff --check` | `git diff --check passed` | 0 |
| `git apply --reverse --check delivery-runtime-fallback.patch` | 临时副本反向检查通过 | 0 |

## 字面输出与哈希

```text
定向测试：BUILD SUCCESSFUL in 12s
全量测试：BUILD SUCCESSFUL in 11s
git diff --check passed
reverse patch check passed
rollback check passed
rollback applied
```

修改态 SHA-256：

- `Job51JobService.java`: `7E2CAFAA00F51BCAACD76F8CCC4F0DFF8577175B8DED97F112E0E5FFDEBCDE16`
- `BossJobService.java`: `46BBBE0B31690B21F13E448661E1C53D35D90E1D183CC81CE09F85505FC366F2`
- `ZhilianJobService.java`: `F129D9321B16DC6CDB284C2794A298C1EE4A360538A159F4135D093F4FF4E2B4`
- `LiepinJobService.java`: `F8DCD1C2C94A5FB792E71380C7BF67C99F47DDF51E39294F00767E9185A93AF1`

隔离回滚后的基线 SHA-256：

- `Job51JobService.java`: `CE474905055481CDDCFF6FC51761CFE1C59F618AEC5CDACE04B356FD2FF4CFE3`
- `BossJobService.java`: `B5B3251B5B8AC44A63BB26B5FE82AB3683ACBDD3BBF039B748E089C5FF2F1975`
- `ZhilianJobService.java`: `D896C148E1B231148C39CC0E733E82FD48D33B6676E12CF6E8390002E501E004`
- `LiepinJobService.java`: `560A71B92C6B6D687464EBAB34836B0BBA483596243998F027F673E7A8A50B09`

## 观察到的行为

- 状态测试进入 `WAITING` 后，调用停止会在 1 秒内收敛到 `CANCELLED`。
- 取消等待路径不会调用浏览器页面入口。
- 后台模式包含 `--headless=new` 且不包含 `--start-maximized`；`visible-login` 保留可见窗口参数。
- 全量 Java 测试覆盖平台锁、生命周期、暂停队列、配置解析和任务状态回归。
