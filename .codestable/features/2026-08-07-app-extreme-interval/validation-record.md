# APP 极限间歇验证记录

## 基线与修改范围

- 基线目录：`baseline/`。
- 目标源码：配置常量、节奏闸门、服务归一化、运行时校验、配置页和两组测试。
- 数据库字段：发送、搜索、翻页、详情、批次大小和批次冷却。
- 目标值：发送 `30-60`、搜索 `10-20`、翻页 `5-10`、详情 `8-15`、批次 `5`、冷却 `300-450` 秒。

## 命令、字面输出与退出码

1. Java 定向测试
   - 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon --tests com.getjobs.worker.liepin.LiepinRateGuardTest --tests com.getjobs.worker.liepin.LiepinConfigTest`
   - 输出：`BUILD SUCCESSFUL`
   - 退出码：`0`
2. Java 全量测试
   - 命令：`$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'; .\gradlew.bat test --no-daemon`
   - 输出：`BUILD SUCCESSFUL`
   - 退出码：`0`
3. 页面 ESLint
   - 命令：`npx eslint app/liepin/page.tsx`
   - 输出：空标准输出
   - 退出码：`0`
4. 页面生产构建
   - 命令：`npm run build`
   - 输出：`Compiled successfully`，路由包含 `/liepin`
   - 退出码：`0`
5. 数据库迁移与读取
   - 输出：`DB_UPDATE_OK (1, 30, 60, 10, 20, 5, 10, 8, 15, 5, 300, 450)`、`DB_FINAL_OK`
   - 退出码：`0`
6. 差异空白检查
   - 命令：`git diff --check`
   - 输出：仅已有 CRLF 提示，无错误
   - 退出码：`0`
7. 源码补丁隔离应用
   - 输出：`PATCH_CHECK_OK`、`PATCH_APPLY_OK`、`PATCH_HASHES_OK`
   - 退出码：`0`
8. 隔离回滚
   - 输出：`ROLLBACK_DB_OK`、`ROLLBACK_DB_VALUES_OK`、`ROLLBACK_SOURCE_OK`、`ROLLBACK_HASHES_OK`
   - 退出码：`0`

## 运行态说明

- 当前源码和数据库已落盘。
- 已加载的后端进程沿用启动时配置；服务重载并启动下一轮任务后读取新档位。
