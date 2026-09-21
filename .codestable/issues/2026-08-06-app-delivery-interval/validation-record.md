---
doc_type: issue-validation
issue: liepin-delivery-interval
date: 2026-08-06
status: verified
---

# 验证记录

## 基线与产物哈希

- `baseline/LiepinRateGuard.java`: `2E09B064169B22AD81A5B64669B0EC7183D593B174F75A00D2640712AA1347EE`
- `baseline/LiepinRateGuardTest.java`: `DFE59A0713B605F9993A6EB0C44D158D2D121DDB096C831EB2F045678D1B484E`
- 修改后 `LiepinRateGuard.java`: `C3D3AD34E527EA1262BDCC0BB1F01FE2155FAA6A41CD8B6F8C2BD3945ECC6FEB`
- 修改后 `LiepinRateGuardTest.java`: `4D991A244233C48DC004B1A17F1C5607081C704E0599E009575DB724DF3671C2`
- 补丁 `liepin-delivery-interval.patch`: `A97A6452E77225ED240C171DBB30EAF3DB0450EB044CCC536B6E1099F518D573`

## 测试

Command: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinRateGuardTest`

Exit code: `0`

Literal output: `BUILD SUCCESSFUL in 25s`

Command: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`

Exit code: `0`

Literal output: `BUILD SUCCESSFUL in 19s`

## 补丁与回滚

Command: `git apply --check --recount --ignore-space-change --ignore-whitespace --directory=target/local-run/rollback-check-20260806-interval .codestable/issues/2026-08-06-app-delivery-interval/liepin-delivery-interval.patch`

Exit code: `0`

Command: `git apply --recount --ignore-space-change --ignore-whitespace --directory=target/local-run/rollback-check-20260806-interval .codestable/issues/2026-08-06-app-delivery-interval/liepin-delivery-interval.patch`

Exit code: `0`

Literal output: `PATCH_CONTENT_CHECK ...LiepinRateGuard.java exit=0; PATCH_CONTENT_CHECK ...LiepinRateGuardTest.java exit=0`

Command: `rollback-liepin-delivery-interval.ps1 -Root target/local-run/rollback-check-20260806-interval`

Exit code: `0`

Literal output:

```text
RESTORED src\main\java\com\getjobs\worker\liepin\LiepinRateGuard.java
RESTORED src\test\java\com\getjobs\worker\liepin\LiepinRateGuardTest.java
HASH_MATCH src/main/java/com/getjobs/worker/liepin/LiepinRateGuard.java=True
HASH_MATCH src/test/java/com/getjobs/worker/liepin/LiepinRateGuardTest.java=True
```

另行从隔离副本直接调用脚本（省略 `-Root`）同样返回 `ROLLBACK_ROOT` 为隔离根目录，两个 `HASH_MATCH` 均为 `True`。

## 结论

等待开始提示先于睡眠分片，等待结束提示位于全部分片之后；已满足间隔时单元测试确认时钟不再前进。批次冷却、停止信号和全量 Java 回归保持通过。
