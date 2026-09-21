# 猎聘零成功计数验证记录

## 基线与最终产物哈希

基线保存在 `baseline/`，未覆盖工作区其他既有改动。

| 文件 | 基线 SHA-256 | 最终 SHA-256 |
|---|---|---|
| `Liepin.java` | `F1F3BE5753F7D162A985D2F4ADD28AA86FA7DE167159897AAC95C49D7FE37752` | `A76F11063BC67DD71A8CADE38EE1D7D46C870B8AC0646FDFFDBDEB984F3F070B` |
| `LiepinJobService.java` | `E7F01DB9004A711288EA92A254C8D630214CD1D107A2CB8B7FB9328BD2297A58` | `BE346DF3BA012C63BA31F3CCA13EDDAD4FD2371D31D8B7CB876873552FE67F89` |
| `LiepinJobServiceDeliveryTest.java` | 新增 | `859AEC409FA890ACF4525F8F1F435B2672343EE9C6EE3E5D4B72BA552ADE93CE` |
| `进度白板.md` | `2F2F24734D160F492D25ABC0BF075C2A44F7189BCCF3CBB9CC6DFE13F1C4DCC1` | `4B51837363541342ED5B18D164D422906CA4E1FA597C4B3E42424C9A0E1060A8` |

## 命令、字面输出与退出码

| 命令 | 字面输出摘要 | 退出码 |
|---|---|---:|
| `rg -n 'processedCount|countedJobIds' src/main/java/com/getjobs/worker/liepin/Liepin.java` | `NO_MATCHES`（包装命令将无匹配转换为成功校验） | 0 |
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest --no-daemon` | `BUILD SUCCESSFUL in 23s` | 0 |
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon` | `BUILD SUCCESSFUL in 16s` | 0 |
| `npm run lint` | `✖ 83 problems (43 errors, 40 warnings)`；均为工作区既有前端规范问题，本轮未改相关文件 | 1 |
| `npm run build` | `✓ Compiled successfully`；`✓ Generating static pages (13/13)` | 0 |
| `git diff --check -- src/main/java/com/getjobs/worker/liepin/Liepin.java src/main/java/com/getjobs/worker/service/LiepinJobService.java` | 空输出；`SCOPED_DIFF_CHECK_EXIT=0` | 0 |
| `git apply --check --directory=.codestable/issues/2026-08-06-liepin-zero-delivery-count/patch-check liepin-zero-delivery-count.patch` | `PATCH_CHECK=0` | 0 |
| `git apply --directory=.codestable/issues/2026-08-06-liepin-zero-delivery-count/patch-check liepin-zero-delivery-count.patch` | `PATCH_APPLY=0`；`PATCH_HASHES_MATCH=1` | 0 |
| 回滚脚本隔离副本执行 | `ROLLBACK_OK`；`ROLLBACK_SCRIPT_PARSE=0`；`ROLLBACK_TARGET_WITHIN_ROOT=True`；`ROLLBACK_HASHES_MATCH=1` | 0 |
| `GET http://127.0.0.1:8888/api/liepin/status`（后端重载后） | `{"messageType":"idle","isRunning":false,"isLoggedIn":true,"message":"尚未启动投递任务","pendingGreeting":null,"platform":"liepin"}` | 0 |

## 已验证行为

1. 发送结果确认成功时，成功数按 `resultList.size()` 增加；跳过、未确认和结果不确定均不增加。
2. 成功数为 0 时，完成消息类型为 `warning`，文案为 `投递任务完成，本轮未成功发起聊天`；正数仍返回 `success` 和对应数量。
3. 补丁可在基线副本中应用，应用后的三个目标文件哈希与工作区一致。
4. 回滚脚本可恢复两处生产源码和进度白板，并移除新增测试。

## 运行状态

后端已从旧进程重载为当前编译结果，8888 端口已恢复监听；当前没有启动投递任务，GET 状态显示 `isLoggedIn=true`。
