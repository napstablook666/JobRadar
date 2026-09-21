# 猎聘实时状态日志验证记录

## 基线
- `src/main/java/com/getjobs/worker/service/LiepinJobService.java` SHA256：`BE346DF3BA012C63BA31F3CCA13EDDAD4FD2371D31D8B7CB876873552FE67F89`
- `front/app/liepin/page.tsx` SHA256：`EF6FCEABD1DF9B2E9A2957AC699409863F225BE039CC7C465AC8D979F24C684D`
- `src/test/java/com/getjobs/worker/service/LiepinJobServiceDeliveryTest.java` SHA256：`859AEC409FA890ACF4525F8F1F435B2672343EE9C6EE3E5D4B72BA552ADE93CE`
- `进度白板.md` SHA256：`CE7671D2880BE0168931747B1C54E61CDD7EA8308DFEE5511FFE7C55B47C6584`
- 基线副本：`baseline/`

## 修改后哈希
- `LiepinJobService.java`：`47819BB260470F75B8EF52797B774444E7A3F47E3EE2CE9EDA831EA22151371B`
- `page.tsx`：`9FBB64E12BE0BF3AA3391AC9AF71C5A0A0027B7D30CFC0CADDEBDCDDB6430E87`
- `LiepinJobServiceDeliveryTest.java`：`3EEF589E70D13C33CC4D1CF70B79C2035D4EE1572D1A9F3897FECE07408546EB`
- `进度白板.md`：`0B19178301B0DCFA34836607CE3492812DC5C9E79591CF37E9424258873CE322`

## 验证命令与结果
- `git diff --check -- src/main/java/com/getjobs/worker/service/LiepinJobService.java front/app/liepin/page.tsx 进度白板.md`
  - 字面输出：`diff-check exit=0`；退出码 `0`。
- `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --tests com.getjobs.worker.service.LiepinJobServiceDeliveryTest`
  - 字面结果：`BUILD SUCCESSFUL`；退出码 `0`。
- `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test`
  - 字面结果：`BUILD SUCCESSFUL in 9s`；退出码 `0`。
- `pnpm exec eslint app/liepin/page.tsx`
  - 字面输出：空；退出码 `0`。
- `pnpm run build`
  - 字面结果：`Compiled successfully`、`Generating static pages (13/13)`；退出码 `0`。
- 在 `patch-check/` 基线副本执行 `git apply --check --unsafe-paths ..\liepin-realtime-status.patch`
  - 字面结果：`patch-check exit=0`；退出码 `0`。
- `rollback-liepin-realtime-status.ps1 -Root rollback-check-final`
  - 字面结果：4 个目标文件均 `equal=True`；退出码 `0`。

## 运行态说明
- 8888 当前进程仍在执行上一轮任务，接口检查结果为：
  - `{"isRunning":true,"hasRecentMessages":false,"recentCount":-1}`；退出码 `0`。
- 这表示当前进程尚未加载本轮构建；任务自然结束后重载后端，页面轮询即可读取 `recentMessages`。

## 产物
- `liepin-realtime-status.patch`
- `rollback-liepin-realtime-status.ps1`
- `baseline-hashes.txt`
