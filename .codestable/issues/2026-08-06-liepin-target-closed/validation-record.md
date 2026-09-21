# 验证记录

## 基线与产物

本轮涉及的主要产物在验证结束后的 SHA-256：

- `PlaywrightManager.java`: `DDF6C60FDD69F7F3DE36E14E7E180B62B669D13E2F910339A85EB32F2E2C33B9`
- `LiepinJobService.java`: `E7F01DB9004A711288EA92A254C8D630214CD1D107A2CB8B7FB9328BD2297A58`
- `Liepin.java`: `F1F3BE5753F7D162A985D2F4ADD28AA86FA7DE167159897AAC95C49D7FE37752`
- `LiepinPageLifecycleTest.java`: `2EBEE5AE2F565B6AD9A8751AEC21B418F0978551F7EF33A989BC5A3193B66909`

## 命令与结果

| 命令 | 字面结果 | 退出码 |
|---|---|---:|
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat compileJava --no-daemon` | `BUILD SUCCESSFUL` | 0 |
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat clean compileJava --no-daemon` | `BUILD SUCCESSFUL`，仅有既有弃用警告 | 0 |
| `gradlew.bat test --tests com.getjobs.worker.liepin.LiepinPageLifecycleTest --tests com.getjobs.worker.liepin.LiepinPageProgressTest --tests com.getjobs.worker.liepin.LiepinRateGuardTest --no-daemon` | `BUILD SUCCESSFUL` | 0 |
| `gradlew.bat test --no-daemon` | `BUILD SUCCESSFUL` | 0 |
| `git diff --check -- <本次涉及源码与测试>` | 空输出 | 0 |
| PowerShell 回滚脚本解析与路径边界检查 | `ROLLBACK_SCRIPT_PARSE=0`, `ROLLBACK_TARGET_WITHIN_ROOT=True` | 0 |

## 已验证行为

1. 页面副作用前的 TargetClosedError 可被识别并进入一次恢复路径。
2. 发送副作用开始后的页面关闭进入结果不确定状态，任务不会自动重复发送。
