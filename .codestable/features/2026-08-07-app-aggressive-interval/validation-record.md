# APP 间歇参数提速验证记录

## 基线与修改范围

- 基线：`baseline-hashes.txt`，包含源码和数据库副本哈希。
- 修改产物：`modified-artifacts.txt`。
- 目标参数：发送 `90-180`、搜索 `30-60`、翻页 `15-30`、详情 `20-40`、批次 `5`、冷却 `600-900` 秒。

## 命令、字面输出与退出码

1. 定向 Java 测试
   - 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinRateGuardTest --tests com.getjobs.worker.liepin.LiepinConfigTest`
   - 输出：`BUILD SUCCESSFUL`
   - 退出码：`0`
2. Java 全量测试
   - 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`
   - 输出：`BUILD SUCCESSFUL`
   - 退出码：`0`
3. 前端定向 ESLint
   - 命令：`npx eslint app/liepin/page.tsx`
   - 输出：空标准输出
   - 退出码：`0`
4. 前端生产构建
   - 命令：`npm run build`
   - 输出：`Compiled successfully`，路由包含 `/liepin`
   - 退出码：`0`
5. 数据库事务更新与读取
   - 输出：`DB_FINAL_OK 90,180,30,60,15,30,20,40,5,600,900`
   - 退出码：`0`
6. 补丁应用检查
   - 输出：`PATCH_CHECK_OK`、`PATCH_APPLY_OK`、`PATCH_HASHES_OK`
   - 退出码：`0`
7. 隔离回滚
   - 输出：`ROLLBACK_DB_OK`、`ROLLBACK_DB_VALUES_OK 120,240,60,120,30,60,45,90,1200,1800`、`ROLLBACK_SOURCE_OK`、`ROLLBACK_HASHES_OK`
   - 退出码：`0`

## 运行态说明

- `8888` 当前任务仍在运行，已加载的本轮配置保持原值。
- 新源码和持久化配置将在服务重载并启动下一轮任务后生效。
